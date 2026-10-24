/**
 * AI 证据文本的展示处理。
 *
 * <p>证据行固定有两行说明：「能支持」与「不能证明」。模型返回的字段本身也常带这句话
 * （实测 5 条里 4 条以「不能证明」开头），标签与正文拼在一起就成了「不能证明不能证明……」。
 * 这里只剥掉与标签重复的那一小句，其余原文一个字不动。
 *
 * <p>不导出成组件内部函数，是为了能直接被验证脚本调用：同一份实现既跑在页面上，
 * 也能用真实捕获的模型输出逐条断言，避免「测的是复制出来的另一份逻辑」。
 */

/** 标签文案唯一来源：模板与去重逻辑必须用同一个字面量，否则改了标签就会重新出现重复 */
export const CANNOT_PROVE_LABEL = '不能证明'

/** 「能支持」行的标签文案，同样必须与去重逻辑共用同一个字面量 */
export const SUPPORTS_LABEL = '能支持'

/**
 * 与标签重复的开头片段：模型会用「不能证明」「无法证明」，偶尔带冒号或逗号。
 *
 * <p>只匹配开头。句中的「不能证明」是正常语义（「不能证明 A，也不能证明 B」），
 * 全局替换会把一句话改成另一种意思。
 */
const DUPLICATED_PREFIX = /^(?:不能证明|无法证明)[：:，,、。；;\s]*/

/**
 * 「能支持」行与标签重复的开头 —— 第一种：与模板标签<b>字面相同</b>的「能支持」。
 *
 * <p>这一种无论后面跟不跟冒号都剥：模板已经写死了「能支持」四个字，
 * 模型再返回一次就一定是重复。真实样本里有「能支持批量导入的文件格式」这种连写形态。
 */
const SAME_AS_LABEL = /^能支持[：:，,、。；;\s]*/

/**
 * 第二种：变体写法「可支持 / 可以支持」。
 *
 * <p>必须带冒号、逗号、空白，<b>或</b>紧跟引号/书名号才算重复标签。
 * 真实样本是「能支持可支持『本次演示选择并预览…』」—— 变体后面直接接中文引号；
 * 而「可支持批量导入的文件格式」是正常语义，剥掉就变成「批量导入的文件格式」，
 * 把原始主张改成了另一种意思，所以汉字开头的变体一律不动。
 */
const VARIANT_LABEL = /^(?:可支持|可以支持)(?:[：:，,、。；;\s]+|(?=[『「“"‘']))/

/**
 * 整句只有标签本身（「能支持」「能支持：」「可支持」）。
 *
 * <p>没有正文可展示，若原样返回，模板会渲染成「能支持能支持」；调用方据此不渲染这一行。
 */
const SUPPORTS_ONLY_LABEL = /^(?:能支持|可支持|可以支持)[：:，,、。；;\s]*$/

/** 「不能证明」行的正文。 */
export function cannotProveText(raw: string | null | undefined): string {
  const text = (raw ?? '').trim()
  if (!text) return ''
  return text.replace(DUPLICATED_PREFIX, '').trim()
}

/**
 * 「能支持」行的正文：剥掉与标签重复的开头，其余原文一个字不动。
 *
 * <p>能识别的重复形态（真实模型输出里都出现过）：
 * <pre>
 *   能支持：演示中完成了 3 条导入   → 演示中完成了 3 条导入
 *   能支持可支持『本次演示…』       → 『本次演示…』
 *   能支持：能支持 3 条记录导入     → 3 条记录导入
 *   能支持批量导入的文件格式       → 批量导入的文件格式
 * </pre>
 *
 * <p>刻意<b>不</b>处理的形态：「可支持批量导入的文件格式」—— 这里的「可支持」是正文的一部分。
 *
 * @param raw 模型返回的 supports 原文
 * @returns 去掉重复开头后的正文；只有标签没有正文时返回空串，调用方据此不渲染这一行
 */
export function supportsText(raw: string | null | undefined): string {
  let text = (raw ?? '').trim()
  if (!text || SUPPORTS_ONLY_LABEL.test(text)) return ''
  // 最多剥 5 层：模型偶尔把标签连写两三遍，每轮都更短，不会死循环
  for (let guard = 0; guard < 5; guard += 1) {
    const pattern = SAME_AS_LABEL.test(text)
      ? SAME_AS_LABEL
      : VARIANT_LABEL.test(text)
        ? VARIANT_LABEL
        : null
    if (!pattern) break
    const next = text.replace(pattern, '').trim()
    if (!next || SUPPORTS_ONLY_LABEL.test(next)) return ''
    text = next
  }
  return text
}
