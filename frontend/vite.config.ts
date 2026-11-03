import { defineConfig } from 'vite'
import uniModule from '@dcloudio/vite-plugin-uni'

// The pinned uni-app plugin is CommonJS. Vite's ESM config loader on newer
// Node versions can expose it as `{ default: plugin }` even though the type
// declaration advertises a direct default export.
const uni = ((uniModule as unknown as { default?: typeof uniModule }).default ?? uniModule)

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname,
    },
  },
})
