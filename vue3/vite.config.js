import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import path from "path";
import {loadEnv} from 'vite'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'

// https://vitejs.dev/config/
export default defineConfig(({mode}) => {
    const env = loadEnv(mode, process.cwd())
    const {VITE_APP_ENV, VITE_APP_BASE_API, VITE_APP_URL} = env
    return {
        base: VITE_APP_ENV === 'production' ? '/' : '/',
        plugins: [
            vue(),
            AutoImport({
                resolvers: [ElementPlusResolver()],
            }),
            Components({
                resolvers: [ElementPlusResolver()],
            }),],

        resolve: {
            alias: {
                // 设置路径
                '~': path.resolve(__dirname, './'),
                // 设置别名
                '@': path.resolve(__dirname, './src')
            },
            extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
        },
        server: {
            // port: 80,
            host: true,
            hmr:true,
            open: false,
            proxy: {
                [VITE_APP_BASE_API]: {
                    target: VITE_APP_URL,
                    changeOrigin: true,
                    rewrite: (p) => p.replace(/^\/stellaris-dev/, ''),
                    bypass(req, res, options) {
                        const realUrl = options.target + (options.rewrite ? options.rewrite(req.url) : '');
                        res.setHeader('A-Real-Url', realUrl); // 添加响应标头(A-Real-Url为自定义命名)，在浏览器中显示
                    },
                },
            },

        },
        build: {
            // Element Plus 作为一个循环依赖较多的 UI 运行时整体打包，约 246 KiB gzip；
            // 强拆为数百个小 chunk 会增加演示环境的请求瀑布，因此按实测体积设置告警阈值。
            chunkSizeWarningLimit: 900,
            rollupOptions: {
                output: {
                    manualChunks(id) {
                        if (!id.includes('node_modules')) return
                        if (id.includes('echarts') || id.includes('zrender')) return 'charts'
                        if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
                        if (id.includes('@vue') || id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) return 'vue-core'
                        if (id.includes('crypto-js') || id.includes('jsencrypt') || id.includes('jsrsasign')) return 'crypto'
                        if (id.includes('quill') || id.includes('@vueup/vue-quill')) return 'editor'
                        return 'vendor'
                    },
                },
            },
        },
    }

})
