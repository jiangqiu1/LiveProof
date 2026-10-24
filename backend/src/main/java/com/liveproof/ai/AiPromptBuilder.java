package com.liveproof.ai;

import com.liveproof.dto.SubtitleCue;
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

    /**
     * 从字幕提取候选核验事项。
     *
     * <p>与 {@link #REVIEW_SYSTEM} 的分工：这里只负责「找出视频里值得核验的承诺」，
     * <b>不做任何核验判断</b>。所以提示词里反复禁止输出结论、禁止补全常识、禁止凑数——
     * 候选是给人挑的，宁可少一条，也不要塞进一条视频里根本没说的话。
     */
    public static final String CANDIDATE_SYSTEM = """
            你是产品演示复盘的选题助手。你会收到一段演示视频的字幕，任务是从中提取「值得人工核验的产品承诺」。

            只做选题，不做核验：
            1. 你只负责找出视频里说过哪些可以被证明或反驳的能力、数量、效果、流程，把它写成待核验事项。
            2. 你<b>不能</b>判断这些承诺是否被实际操作证明。不要写「已验证」「支持良好」「存在风险」这类结论。
            3. claim 必须是「视频声称了什么」的客观转述，不是「事实是什么」。

            提取原则：
            4. 只提取字幕里<b>明确出现</b>的承诺。字幕没提到的，一律不提取；不得用常识、行业惯例或产品常见功能补全。
            5. 同一件事只说一次。多条字幕讲同一件事时合并成一条，时间区间覆盖最长的那段，不要拆成多条。
            6. 纯寒暄、纯操作口令（「点这里」「好的」「稍等一下」）、没有可核验含义的界面描述，不要提取。
            7. 承诺越具体越值得提取（带数量、条件、范围的优先）；「很好用」「很方便」这类无法核对的形容词不要单独成条。
            8. startSec / endSec 必须取自给定字幕的时间，不得编造；区间应尽量贴住原话，不要拉成整段视频。
            9. 如果整段字幕里没有可核验的产品承诺，candidates 返回空数组——这是合法结果，绝对不要为了凑数硬造。

            输出要求：
            - 只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释性前后缀。
            - candidates 最多 8 条，按 startSec 升序。
            - quote 填该区间对应的字幕原文（可截取关键句），必须来自给定字幕，不得改写或拼接。
            - question 写成一句可直接核验的中文提问，claim 写成一句陈述，两者都不要出现「据说是」「可能」这类模糊词。

            JSON schema：
            {
              "candidates": [
                {
                  "question": "待核验的问题",
                  "claim": "视频里的原话要点",
                  "startSec": 0,
                  "endSec": 0,
                  "quote": "该区间的字幕原文"
                }
              ]
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

    /**
     * 候选提取的 user 消息：素材元信息 + 整段字幕。
     *
     * <p>与核验不同，这里<b>不做</b>时间窗裁剪——候选要覆盖整段演示，
     * 只按字符预算做均匀抽样，并如实告知模型「这是抽样后的字幕」，
     * 避免它把「抽样里没看到」说成「视频里没有」。
     */
    public static String candidateUserPrompt(String materialName, String materialId, String sourceLabel,
                                             Double durationSec, List<SubtitleCue> cues, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 演示素材\n");
        sb.append("- 素材名称：").append(nullToDash(materialName)).append('\n');
        sb.append("- 素材 id：").append(nullToDash(materialId)).append('\n');
        sb.append("- 视频时长：")
                .append(durationSec == null ? "未知" : fmt(durationSec) + "（" + trimNum(durationSec) + " 秒）")
                .append('\n');
        sb.append("- 字幕来源：").append(sourceLabel).append("\n\n");

        sb.append("## 字幕（").append(cues.size()).append(" 条，按时间升序）\n");
        if (truncated) {
            sb.append("（字幕总长超过单次提交上限，下面是均匀抽样后的结果；")
                    .append("没出现在这里的内容只代表本次未提交，不代表视频里没说）\n");
        }
        sb.append(cues.stream()
                .map(cue -> "[cue %d] %s-%s  %s".formatted(
                        cue.index(), fmt(cue.startSec()), fmt(cue.endSec()),
                        cue.text().replace("\n", " ")))
                .collect(Collectors.joining("\n")));
        sb.append("\n\n");

        sb.append("## 任务\n");
        sb.append("从上面的字幕里提取值得人工核验的产品承诺，严格按 system 中的 JSON schema 输出。\n");
        sb.append("只提取字幕里明确说过的东西；没有任何值得核验的承诺时，返回 {\"candidates\": []}，不要硬造。\n");
        return sb.toString();
    }

    /** 秒数保留一位小数，避免把 106.47 写成 106.47000000000001。 */
    private static String trimNum(double sec) {
        return java.math.BigDecimal.valueOf(sec)
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
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
