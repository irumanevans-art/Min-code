package dev.min.agent.tools

/*
 * notebook 测试共用的样本，手写成 Jupyter 自己写文件的样子：缩进 1 空格、键按字母序、末尾换行。
 * 期望值都由这些片段拼出来，不靠被测代码生成。
 */

internal const val NB_HEAD = "{\n \"cells\": [\n"

internal const val NB_TAIL = " \"metadata\": {\n" +
    "  \"kernelspec\": {\n" +
    "   \"display_name\": \"Python 3\",\n" +
    "   \"language\": \"python\",\n" +
    "   \"name\": \"python3\"\n" +
    "  }\n" +
    " },\n" +
    " \"nbformat\": 4,\n" +
    " \"nbformat_minor\": 5\n" +
    "}\n"

internal val MD_CELL = """
  {
   "cell_type": "markdown",
   "id": "md000001",
   "metadata": {},
   "source": [
    "# 标题\n",
    "正文"
   ]
  }""".trimStart('\n')

internal val CODE_CELL = """
  {
   "cell_type": "code",
   "execution_count": 3,
   "id": "code0002",
   "metadata": {
    "tags": [
     "x"
    ]
   },
   "outputs": [
    {
     "name": "stdout",
     "output_type": "stream",
     "text": [
      "hi\n"
     ]
    }
   ],
   "source": [
    "print('hi')"
   ]
  }""".trimStart('\n')

internal val RAW_CELL = """
  {
   "cell_type": "raw",
   "id": "raw00003",
   "metadata": {},
   "source": [
    "raw text"
   ]
  }""".trimStart('\n')

/** 用给定的单元（各自已带 2 格缩进、无结尾逗号）拼出整本 notebook */
internal fun nb(vararg cells: String): String =
    if (cells.isEmpty()) "{\n \"cells\": [],\n$NB_TAIL"
    else NB_HEAD + cells.joinToString(",\n") + "\n ],\n" + NB_TAIL

internal val NB1 = nb(MD_CELL, CODE_CELL, RAW_CELL)

/** 缩进加倍（1 空格风格 → 2 空格风格），只用来造「别的缩进风格」的输入与期望 */
internal fun String.doubleIndent(): String = lines().joinToString("\n") { l ->
    " ".repeat(l.length - l.trimStart(' ').length) + l
}
