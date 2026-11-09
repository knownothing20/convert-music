# 音乐转换 convert-music

在浏览器里把已购的加密音乐文件还原成标准 **MP3 / FLAC**。全程在本地完成，文件不会上传到任何服务器。

> 本项目基于开源项目 [unlock-music](https://git.unlock-music.dev/um/web) 的构建产物，按 MIT 许可保留原版权声明与许可证文件。

## 支持格式

| 来源 | 后缀 |
| --- | --- |
| 网易云音乐 | `.ncm` |
| QQ 音乐 | `.qmc0` `.qmc2` `.qmc3` `.qmcflac` `.qmcogg` `.tkm` |
| QQ 音乐新格式 | `.mflac` `.mgg` `.mflac0` `.mgg1` `.mggl` |
| QQ 音乐 Tm | `.tm0` `.tm2` `.tm3` `.tm6` |
| 虾米 / 酷我 / 酷狗 | `.xm` `.kwm` `.kgm` `.vpr` |
| 咪咕 / 喜马拉雅 | `.mg3d` `.x2m` `.x3m` |

## 本地使用

`index.html` 不能直接双击打开（PWA 与 Service Worker 需要 http 协议），起一个本地服务即可：

```bash
python -m http.server 8080
```

然后浏览器访问 <http://localhost:8080>。

## 直接安装 Android 版（APK）

每次推送到 `main`，GitHub Actions 会自动构建 APK 并发布到 Release，固定下载地址：

```
https://github.com/knownothing20/convert-music/releases/latest/download/convert-music.apk
```

- `android/` 是一个 WebView 壳工程，与网页共用同一份 `index.html`（构建时自动复制进 assets）
- debug 签名，安装时需要允许「未知来源」
- 最低系统 Android 10（API 29）
- 转换结果保存在「音乐 / 音乐转换」文件夹，走 MediaStore，不需要存储权限
- WebView 不支持 `blob:` 下载，因此页面会在检测到原生桥接时改用分块传输写盘

## 部署到 GitHub Pages

1. 进入仓库 **Settings → Pages**
2. **Build and deployment → Source** 选择 **GitHub Actions**（不要选 Deploy from a branch）
3. 向 `main` 分支推送后会自动构建
4. 访问 `https://<你的用户名>.github.io/convert-music/`

工作流文件位于 `.github/workflows/static.yml`。

## 相对原始版本的改动

- **移除第三方统计上报**：原版会向 `stats.ixarea.com` 发送使用数据。这里在页面加载前安装了请求拦截器，阻断该域名的 fetch / XHR / sendBeacon 请求，不改动已压缩的业务代码。
- 页面标题、描述、PWA 应用名改为「音乐转换」，`apple-mobile-web-app-title` 改为 `convert-music`。
- GitHub Actions 配置升级到新版 Pages Action（`checkout@v4` / `configure-pages@v5` / `upload-pages-artifact@v3` / `deploy-pages@v4`），兼容当前运行环境。
- 新增 `.nojekyll`，避免 GitHub Pages 用 Jekyll 处理静态资源。

## 使用须知

- 解密全部在浏览器本地进行，不联网、不上传。
- **浏览器沙箱限制**：无法扫描整个文件夹，也无法删除你磁盘上的原始文件，转换完成后需要自行清理 `.ncm` 等源文件。
- 仅供个人已购内容的格式迁移，请遵守各平台服务条款与著作权法律法规。

## 许可

MIT License，见 [LICENSE](LICENSE)。原始版权归 unlock-music 项目作者所有。
