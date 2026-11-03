<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { api } from '@/api'

onLaunch(async () => {
  await api.bootstrap()
})
</script>

<style lang="scss">
page {
  min-height: 100%;
  background: #f4f6f8;
  color: #1c2524;
}
</style>
