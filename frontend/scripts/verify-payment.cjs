/* Runs source modules in memory with a fake uni transport/storage; no emitted files or dependencies. */
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const ts = require('typescript')
const root = path.resolve(__dirname, '../src')
const storage = new Map()
const requests = []
let response = { statusCode: 200, data: {} }
let networkFailure = false
let requestHandler
global.uni = {
  getStorageSync: (key) => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
  request: (options) => {
    requests.push(options)
    if (requestHandler) return requestHandler(options)
    if (networkFailure) options.fail({ errMsg: 'test offline' })
    else options.success(response)
  },
}

const modules = new Map()
function load(filename) {
  const resolved = path.resolve(filename.endsWith('.ts') ? filename : `${filename}.ts`)
  if (modules.has(resolved)) return modules.get(resolved).exports
  const module = { exports: {} }
  modules.set(resolved, module)
  const source = fs.readFileSync(resolved, 'utf8')
    .replaceAll('import.meta.env.VITE_API_MODE', '"http"')
    .replaceAll('import.meta.env.VITE_API_BASE_URL', '"http://localhost:8080"')
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText
  const localRequire = (id) => id.startsWith('@/') ? load(path.join(root, id.slice(2)))
    : id.startsWith('.') ? load(path.resolve(path.dirname(resolved), id)) : require(id)
  vm.runInThisContext(`(function(require,module,exports){${compiled}\n})`, { filename: resolved })(localRequire, module, module.exports)
  return module.exports
}

async function verify() {
  const { HttpStatusError, isQuotaExceeded, isDefinitiveRejection } = load(path.join(root, 'api/http-client'))
  assert.equal(isQuotaExceeded(new HttpStatusError(429, 'daily limit', 'AI_DAILY_QUOTA_EXCEEDED')), true)
  assert.equal(isQuotaExceeded(new HttpStatusError(429, 'rate limited', 'RATE_LIMITED')), false)
  assert.equal(isQuotaExceeded(new HttpStatusError(429, 'rate limited')), false)
  for (const status of [400, 401, 403, 404, 422]) {
    assert.equal(isDefinitiveRejection(new HttpStatusError(status, 'rejected')), true)
  }
  for (const status of [408, 409, 429, 500, 502]) {
    assert.equal(isDefinitiveRejection(new HttpStatusError(status, 'uncertain')), false)
  }
  assert.equal(isDefinitiveRejection(new Error('network timeout')), false)
  assert.equal(isDefinitiveRejection(new HttpStatusError(409, 'conflict', 'PAYMENT_IDEMPOTENCY_CONFLICT')), true)
  const { MockPaymentApi, consumeMockModelCall, shanghaiDate } = load(path.join(root, 'api/payment-mock'))
  const api = new MockPaymentApi()
  assert.match((await api.getEntitlement()).userId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.equal((await api.getQuota()).userId, (await api.getEntitlement()).userId)
  assert.deepEqual((await api.listPlans()).map((plan) => plan.planCode), ['PLUS'])
  assert.ok((await api.listPlans()).every((plan) => plan.priceCents >= 1 && plan.durationDays >= 1))
  await assert.rejects(api.createOrder('UNKNOWN', 'unknown-plan'), (error) => error.status === 404 && error.code === 'PAYMENT_PLAN_NOT_FOUND')
  await assert.rejects(api.getOrder('missing'), (error) => error.status === 404 && error.code === 'PAYMENT_ORDER_NOT_FOUND')
  await assert.rejects(api.mockPay('missing'), (error) => error.status === 404 && error.code === 'PAYMENT_ORDER_NOT_FOUND')
  assert.equal(shanghaiDate(Date.parse('2026-11-02T15:59:59Z')), '2026-11-02')
  assert.equal(shanghaiDate(Date.parse('2026-11-02T16:00:00Z')), '2026-11-03')
  for (let index = 0; index < 10; index += 1) consumeMockModelCall()
  assert.throws(() => consumeMockModelCall(), (error) => isQuotaExceeded(error))

  const order = await api.createOrder('PLUS', 'purchase-key')
  assert.equal((await api.createOrder('PLUS', 'purchase-key')).orderId, order.orderId)
  await assert.rejects(api.createOrder('FREE', 'purchase-key'), (error) => error.status === 409 && error.code === 'PAYMENT_IDEMPOTENCY_CONFLICT')
  assert.equal((await api.listOrders()).length, 1)
  assert.equal(order.priceCents, 1990)
  await api.mockPay(order.orderId)
  const paidEnd = (await api.getEntitlement()).validUntil
  assert.equal((await api.getQuota()).usedModelCalls, 10)
  assert.equal((await api.getQuota()).remainingModelCalls, 40)
  await api.mockPay(order.orderId)
  assert.equal((await api.getEntitlement()).validUntil, paidEnd)
  const renewal = await api.createOrder('PLUS', 'renewal-key')
  await api.mockPay(renewal.orderId)
  assert.equal(Date.parse((await api.getEntitlement()).validUntil) - Date.parse(paidEnd), 30 * 86_400_000)

  const stateKey = 'climbing-payment-mock-state-v1'
  let state = JSON.parse(storage.get(stateKey))
  state.entitlement.validUntil = new Date(Date.now() - 1000).toISOString()
  storage.set(stateKey, JSON.stringify(state))
  assert.equal((await api.getQuota()).planCode, 'FREE')
  assert.equal((await api.getQuota()).usedModelCalls, 10)
  state = JSON.parse(storage.get(stateKey))
  state.usageDate = '2000-01-01'
  storage.set(stateKey, JSON.stringify(state))
  assert.equal((await api.getQuota()).usedModelCalls, 0)
  const expired = await api.createOrder('PLUS', 'expired-key')
  state = JSON.parse(storage.get(stateKey))
  state.orders.find((item) => item.orderId === expired.orderId).expiresAt = new Date(Date.now() - 1).toISOString()
  storage.set(stateKey, JSON.stringify(state))
  await assert.rejects(api.mockPay(expired.orderId), (error) => error.code === 'PAYMENT_ORDER_EXPIRED')

  storage.clear()
  const { MockAssessmentApi } = load(path.join(root, 'api/mock'))
  const agent = new MockAssessmentApi()
  const assessment = await agent.createAssessment({ trigger: 'grip' })
  let session = await agent.startAgent(assessment.id)
  await agent.getAgentSession(assessment.id)
  assert.equal((await api.getQuota()).usedModelCalls, 1)
  for (const answer of ['sting', 'none', 'bend']) session = await agent.answerAgent(session.id, answer)
  assert.equal(session.status, 'COMPLETED')
  assert.equal((await api.getQuota()).usedModelCalls, 4)
  for (let index = 0; index < 6; index += 1) consumeMockModelCall()
  const next = await agent.createAssessment({ trigger: 'grip' })
  await assert.rejects(agent.startAgent(next.id), (error) => isQuotaExceeded(error))
  assert.equal(await agent.getAgentSession(next.id), undefined)

  const { HttpPaymentApi, paymentApi, isMockMode } = load(path.join(root, 'api/payment'))
  assert.equal(isMockMode, false)
  assert.ok(paymentApi instanceof HttpPaymentApi)
  const http = new HttpPaymentApi('http://localhost:8080/')
  storage.set('access-token', 'test-only-token')
  response = { statusCode: 201, data: order }
  assert.equal((await http.createOrder('PLUS', 'request-key')).orderId, order.orderId)
  let last = requests.at(-1)
  assert.equal(last.url, 'http://localhost:8080/api/payment/v1/orders')
  assert.equal(last.header.Authorization, 'Bearer test-only-token')
  assert.equal(last.header['X-Idempotency-Key'], 'request-key')
  assert.equal(last.header['X-User-Id'], undefined)
  assert.deepEqual(last.data, { planCode: 'PLUS' })
  response = { statusCode: 200, data: [] }
  await http.listPlans()
  assert.equal(requests.at(-1).url, 'http://localhost:8080/api/payment/v1/plans')
  await http.listOrders()
  assert.equal(requests.at(-1).url, 'http://localhost:8080/api/payment/v1/orders?limit=20')
  await http.getOrder('order-id')
  assert.equal(requests.at(-1).url, 'http://localhost:8080/api/payment/v1/orders/order-id')
  await http.getEntitlement()
  assert.equal(requests.at(-1).url, 'http://localhost:8080/api/payment/v1/entitlement')
  response = { statusCode: 200, data: { status: 'PAID' } }
  await http.mockPay('order-id')
  last = requests.at(-1)
  assert.equal(last.url, 'http://localhost:8080/api/payment/v1/orders/order-id/mock-pay')
  assert.equal(last.method, 'POST')
  assert.equal(Object.hasOwn(last, 'data'), false)
  response = { statusCode: 403, data: { code: 'MOCK_PAYMENT_DISABLED', detail: 'disabled' } }
  await assert.rejects(http.mockPay('order-id'), (error) => error.status === 403 && error.code === 'MOCK_PAYMENT_DISABLED')

  // A saved answer followed by a quota-denied advance must recover without another model attempt.
  const { HttpAssessmentApi } = load(path.join(root, 'api/http'))
  const httpAgent = new HttpAssessmentApi('http://localhost:8080')
  const sessionId = '00000000-0000-4000-8000-000000000101'
  const assessmentId = '00000000-0000-4000-8000-000000000102'
  let saved = {
    sessionId, assessmentId, status: 'WAITING_FOR_USER', currentStepNumber: 1, recentSteps: [],
    version: 1, activeCheckpointId: '00000000-0000-4000-8000-000000000103', updatedAt: new Date().toISOString(),
    question: { questionId: '00000000-0000-4000-8000-000000000104', questionKey: 'SYMPTOM_QUALITY', prompt: 'Describe it', answerType: 'TEXT', required: true },
  }
  let answerPosts = 0
  let advances = 0
  requestHandler = (options) => {
    if (options.method === 'POST' && options.url.endsWith('/responses')) {
      answerPosts += 1
      assert.equal(options.data.answer, 'sting')
      saved = { ...saved, status: 'CREATED', question: null, version: 2 }
      options.success({ statusCode: 200, data: JSON.parse(JSON.stringify(saved)) })
    } else if (options.method === 'POST' && options.url.endsWith('/advance')) {
      advances += 1
      options.success({ statusCode: 429, data: { code: 'AI_DAILY_QUOTA_EXCEEDED', detail: 'daily quota exhausted' } })
    } else if (options.method === 'GET' && (options.url.endsWith(`/${sessionId}`) || options.url.includes('/active?'))) {
      options.success({ statusCode: 200, data: JSON.parse(JSON.stringify(saved)) })
    } else throw new Error(`Unexpected Agent request: ${options.method} ${options.url}`)
  }
  await httpAgent.getAgentSession(assessmentId, false)
  await assert.rejects(httpAgent.answerAgent(sessionId, 'sting'), isQuotaExceeded)
  const recovered = await httpAgent.getAgentSession(assessmentId, false)
  assert.equal(answerPosts, 1)
  assert.equal(advances, 1)
  assert.equal(recovered.exchanges[0].answer, 'sting')
  assert.equal(recovered.exchanges.find((exchange) => !exchange.answer), undefined)
  requestHandler = undefined
  networkFailure = true
  await assert.rejects(paymentApi.getQuota(), /test offline/)
  assert.equal(requests.at(-1).url, 'http://localhost:8080/api/ai/v1/quota')
  console.log('PASS: Shanghai rollover, quota/429, order/payment idempotency, renewal/expiry, usage preservation, Mock Agent consumption, saved-answer read-only recovery, HTTP/Bearer/error boundaries.')
}

verify().catch((error) => { console.error(error); process.exitCode = 1 })
