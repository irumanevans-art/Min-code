package dev.min.code.core.engine.min

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.spi.FeedbackSink
import dev.min.agent.spi.PreviewFile
import dev.min.agent.spi.PreviewSink
import dev.min.code.ui.viewer.ViewerInbox

/*
 * 模型的 Artifact / SendFeedback 在手机上的出口：只把东西放进 [ViewerInbox]，由界面决定何时显示。
 * 两个 Sink 都不上网、不写盘、不发送；草稿要不要导出、导出后发给谁，是用户在界面上的动作。
 */

/** Artifact 的本机预览：放进收件箱，预览面板看到就打开。收件箱总是接得住，所以返回 true */
internal class AndroidPreviewSink : PreviewSink {
    override suspend fun show(file: PreviewFile): Boolean {
        ViewerInbox.showArtifact(file)
        return true
    }
}

/** SendFeedback 的草稿队列：放进收件箱，用户在「诊断包草稿」面板里看、改、导出或丢弃。不发送 */
internal class AndroidFeedbackSink : FeedbackSink {
    override suspend fun queue(draft: FeedbackDraft): Boolean {
        ViewerInbox.addDraft(draft)
        return true
    }
}
