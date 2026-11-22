/*
 * Project:    Baicodex
 * License:    AGPL-3.0 (GNU Affero General Public License v3.0)
 */

package b.ai.core.misc

/**
 * 带成败的结果。
 *
 * 用在「产生结果的这一层本来就知道成没成」的地方：拉云端、查车票、跑搜索。
 * 上层再也不用从中文文案里猜成败——改一句文案就把成功翻成失败的坑，就是这么来的。
 */
data class Res(val ok: Boolean, val text: String) {

    companion object {
        fun ok(text: String) = Res(true, text)
        fun fail(text: String) = Res(false, text)
    }
}
