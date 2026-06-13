import { defineConfig } from 'electron-vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'
import { vite as vidstack } from 'vidstack/plugins';
import path from 'path'

export default defineConfig({
  main: {},
  preload: {},
  renderer: {
    plugins: [vidstack(), tailwindcss(), svelte()],
    resolve: {
      alias: {
        $lib: path.resolve(__dirname, './src/lib')
      }
    }
  }
})
