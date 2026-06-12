import { defineConfig } from 'electron-vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  main: {},
  preload: {},
  renderer: {
    plugins: [tailwindcss(), svelte()],
    resolve: {
      alias: {
        $lib: path.resolve(__dirname, './src/lib')
      }
    }
  }
})
