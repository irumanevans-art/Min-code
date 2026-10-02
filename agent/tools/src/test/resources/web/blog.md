# 把网页喂给大模型之前，先转成 Markdown

2026-06-14 · 作者 [Min](https://blog.example.com/authors/min) · 阅读约 8 分钟

直接把 **HTML** 扔给模型又长又乱：标签是噪音，样式脚本全无用， 真正的内容被淹没了。转成 *干净的 Markdown* 再喂，token 少一大半，效果反而更好。

我们的做法写在 `agent/tools/src/main/kotlin/dev/min/agent/tools/web/` 里， 核心是一个 [Kotlin](https://kotlinlang.org/docs/multiplatform.html) 纯函数，见 [下面的设计一节](https://blog.example.com/2026/06/html-to-markdown.html#design)，也可以先看[总览](https://blog.example.com/2026/index.html)。

> 垃圾进，垃圾出；先把垃圾筛掉，模型才有得用。
>
> ——某位不愿透露姓名的工程师

## 为什么要自己写解析器

jsoup 很好，但 Min 的依赖树是按字节数精打细算的。而且网页 HTML 常常不规范： 没闭合的 `<p>`、多余的闭合标签、属性不带引号——这些都要容错。

- 单遍扫描，耗时与输入长度成正比
- 任何输入都不抛异常
- 正文优先：main > article > body

完整基准见 [min-bench](https://github.com/example/min-bench) 仓库。
