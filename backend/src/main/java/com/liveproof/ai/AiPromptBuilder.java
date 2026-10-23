package com.liveproof.ai;

import com.liveproof.entity.CaseEntity;
import com.liveproof.service.EvidenceContextService;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词构建。
 *
 * <p>约束全部写在 system 里，材料只放在 user 中，便于后续复用与审查。
 * 提示词要求模型只做「证据建议」，不产出最终结论，也不允许凭空补数字。
 */
public final class AiPromptBuilder {

    private AiPromptBuilder() {
    }

    public static final String REVIEW_SYSTEM = """
            你是视频演示证据核验助手。你的任务只针对一条复盘事项，基于给定字幕与关键帧，提出有出处的核验建议。

            必须遵守的判断原则：
            1. 你给出的任何判断都只是建议，不是最终结论；不要输出「已确认」「已通过」之类的结论性表述。
            2. 字幕里出现某句话，不等于实际操作成功；识别到某个功能界面，不等于功能已成功执行。
            3. 只有口头承诺（没有操作画面或结果展示）时，证据形态必须是 verbal_claim。
            4. 当前给出的片段里没找到证据，不等于该功能不存在，只能说本次未找到或证据不足。
            5. 画面不清晰、关键操作被遮挡、结果未展示时，必须写明「无法确认」，不要猜。
            6. 不要根据一个字段推断另一个字段：回答状态、证据形态、证据充分性三者独立判断。
            7. 不得编造未在材料中出现的数字、界面文案、时间点。引用时间必须来自给定的字幕或关键帧。
            8. 若材料不足以支撑任何结论，answerStatus 用 not_found、evidenceType 用 unknown、evidenceSufficiency 用 insufficient，并在 limitations 说明原因。

            输出要求：
            - 只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释性前后缀。
            - 字段与取值严格按下述 schema，枚举只能用给定字面量。
            - summary、reasoning、limitations 用中文，简洁、可核对。limitations 必须写，不能留空，不能只写「无」。
            - evidenceItems 最多 5 条，按时间升序；每条必须给出 startSec/endSec，且落在给定材料范围内。
            - evidenceItems 的每一条都必须同时写 supports 和 cannotProve，两者都不能为空：
              supports 说明「这一段能支持什么结论」，cannotProve 说明「这一段不能证明什么」。
              不要把这些话写进 reasoning 就当作已完成——reasoning 是整体判断依据，不能替代逐条的说明字段。
              subtitle 填该区间对应字幕原文，observation 填该区间画面的具体观察（如界面文字、进度、列表条目）；
              确实没有就填 null，但 supports 与 cannotProve 仍然必须写。
            - suggestedTimePoints 只放你自己认为最值得人工回看的时刻（最多 6 个），必须是给定字幕或关键帧覆盖的时刻；没有就返回空数组。
            - unresolvedQuestions 是仍需人工确认的问题，最多 5 条；没有就返回空数组。

            自检：输出前确认每一条 evidenceItems 都有非空的 supports 与 cannotProve，且 limitations 非空。缺任何一项都视为不合格输出。

            JSON schema：
            {
              "summary": "对当前事项的简短分析",
              "answerStatus": "answered | partially_answered | not_found",
              "evidenceType": "verbal_claim | specification_display | operational_evidence | unknown",
              "evidenceSufficiency": "not_checked | insufficient | sufficient",
              "reasoning": "判断依据，指明依据的是哪条字幕或哪一帧",
              "limitations": "无法确认的部分、潜在误判与证据局限",
              "evidenceItems": [
                {
                  "startSec": 0,
                  "endSec": 0,
                  "subtitle": "对应字幕原文，没有就填 null",
                  "observation": "画面观察，没看到画面就填 null",
                  "supports": "该片段能支持什么结论",
                  "cannotProve": "该片段不能证明什么",
                  "frameSec": null
                }
              ],
              "suggestedTimePoints": [0],
              "unresolvedQuestions": ["仍需人工确认的问题"]
            }
            """;

    public static final String NARRATIVE_SYSTEM = """
            你是产品演示复盘报告的归纳助手。你会收到一份由服务端从数据库统计好的事实清单。
            要求：
            1. 只能基于给定事实归纳，不得新增任何数字、比例、排名，也不得复述未提供的信息。
            2. 不得改变任何已保存的核验状态，不得把「未检查」说成「已核验」，不得把「本次未找到证据」说成「功能不存在」。
            3. 输出中文，语气克制，区分事实与推断；推断必须写明「推测」。
            4. 只输出 JSON：{"overview": "两到三句话的总体判断", "findings": ["关键发现 1", "关键发现 2"], "risks": ["需要人工确认的风险"]}
            """;

    public static String reviewUserPrompt(CaseEntity entity, EvidenceContextService.Prepared prepared,
                                          List<FrameImage> frames, boolean visionUsed, String frameNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 复盘事项\n");
        sb.append("- 事项 ID：").append(entity.getId()).append('\n');
        sb.append("- 待核验问题：").append(nullToDash(entity.getQuestion())).append('\n');
        sb.append("- 相关功能承诺：").append(nullToDash(entity.getClaim())).append('\n');
        sb.append("- 数据源标注的演示区间：").append(range(entity.getStartSec(), entity.getEndSec())).append('\n');
        sb.append("- 视频文件：").append(nullToDash(entity.getVideo())).append('\n');
        sb.append("- 视频总时长：")
                .append(prepared.durationSec() == null ? "未知" : fmt(prepared.durationSec()) + "（秒）")
                .append("\n\n");

        sb.append("## 当前已保存的核验结果（仅供参考，可能不正确，不要照抄）\n");
        sb.append("- 核验进度 reviewStatus：").append(entity.getReviewStatus()).append('\n');
        sb.append("- 回答状态 answerStatus：").append(entity.getAnswerStatus()).append('\n');
        sb.append("- 证据形态 evidenceType：").append(entity.getEvidenceType()).append('\n');
        sb.append("- 证据充分性 evidenceSufficiency：").append(entity.getEvidenceSufficiency()).append('\n');
        sb.append("- 已写的核验依据：").append(nullToDash(entity.getReasoning())).append('\n');
        sb.append("- 已写的局限与风险：").append(nullToDash(entity.getLimitations())).append("\n\n");

        sb.append("## 字幕材料（SRT，本地演示字幕）\n");
        if (prepared.cues().isEmpty()) {
            sb.append("（没有可用字幕）\n");
        } else {
            if (prepared.truncated()) {
                sb.append("（字幕较多，已按与问题的相关度裁剪）\n");
            }
            sb.append(prepared.cues().stream()
                    .map(cue -> "%s  %s-%s%s%n%s".formatted(
                            "[cue " + cue.index() + "]",
                            fmt(cue.startSec()),
                            fmt(cue.endSec()),
                            prepared.outside().contains(cue.index()) ? "  （位于原始演示区间之外）" : "",
                            "  " + cue.text().replace("\n", " ")))
                    .collect(Collectors.joining("\n")));
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 关键帧材料\n");
        if (frames.isEmpty()) {
            sb.append("本次没有可用的视频画面：").append(nullToDash(frameNote)).append('\n');
            sb.append("因此不要对画面内容做任何描述，observation 一律填 null，并在 limitations 里写明未进行画面核验。\n");
        } else {
            sb.append("已附上 ").append(frames.size())
                    .append(" 帧画面（按顺序出现在本条消息的图片块中），时间点分别为：")
                    .append(frames.stream().map(f -> fmt(f.sec())).collect(Collectors.joining("、")))
                    .append('\n');
            sb.append("描述画面时请引用对应时间点；没有对应画面的证据，frameSec 填 null。\n");
        }
        if (!visionUsed && !frames.isEmpty()) {
            sb.append("注意：本次图片未能送达模型，你只能基于字幕判断。\n");
        }

        sb.append("\n## 任务\n");
        sb.append("针对上面的复盘事项，判断视频是否对该问题给出了回答、证据形态是什么、证据是否充分，");
        sb.append("并给出可回看的时间点。严格按 system 中的 JSON schema 输出：");
        sb.append("每条 evidenceItems 都要有非空的 supports 与 cannotProve，limitations 也要非空。\n");
        return sb.toString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }

    private static String range(BigDecimal start, BigDecimal end) {
        if (start == null && end == null) {
            return "未标注（按整段视频处理）";
        }
        return fmt(start == null ? 0 : start.doubleValue()) + " - "
                + (end == null ? "视频末尾" : fmt(end.doubleValue()));
    }

    static String fmt(double sec) {
        if (!Double.isFinite(sec)) {
            return "--:--";
        }
        int total = (int) Math.round(Math.max(0, sec));
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}
