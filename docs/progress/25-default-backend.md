# Android 默认后端

日期：2026-10-17。版本：`1.4.4-default-server`，versionCode 14。

按用户要求，ProductClient 未保存 endpoint 时默认使用：
`https://sit-p-19357380370-lookbuddy-8080-hackathon.anker-launch.com`。

保留用户已保存的地址，不覆盖自定义环境，也不删除账号数据。升级后若原来没有保存地址，会直接采用新默认值；若曾手动保存模拟器或其他地址，仍可在“个人设置→系统设置→后端设置”修改。地址输入标签不再提示模拟器为默认值。集成构建的debug/release API_BASE_URL声明也与此HTTPS地址保持一致。

本次仅改Android默认连接及版本，不读取服务端配置或凭据，不访问线上账号，不发布后端。构建和APK校验结果记录在 `output/default-server-20261017/manifest.json`；保留统一版本已有聊天、日记与导航功能。
