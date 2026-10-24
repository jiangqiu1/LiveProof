package com.liveproof.service;

import com.liveproof.dto.CaseCandidateBatchRequest;
import com.liveproof.dto.CaseCandidateBatchResult;
import com.liveproof.dto.CaseCreateRequest;
import com.liveproof.dto.CaseDetail;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.ApiException;
import com.liveproof.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 把用户勾选过的 AI 候选事项写入数据库。
 *
 * <p>红线与手动新增、CSV 导入完全一致：
 * <ul>
 *   <li>来源标记为 {@code ai}，但<b>不改变</b>任何核验字段的默认值——
 *       写进来的一律 {@code reviewStatus=unreviewed}、{@code answerStatus=NULL}、
 *       {@code evidenceSufficiency=not_checked}。「AI 帮你想的问题」和「AI 帮你下了结论」是两件事。</li>
 *   <li>任一条不合法就整批拒绝（422），不做「跳过坏的那条悄悄写入」。</li>
 * </ul>
 *
 * <p>为什么校验要在写入前单独做一遍：{@link CaseService#create} 是逐条抛异常的，
 * 若边创建边失败，事务虽然会回滚，但用户只会看到第一条错误，
 * 不知道后面还有几条也有问题。这里一次性把所有条目的问题收集齐再返回。
 */
@Service
public class CaseCandidateService {

    private static final Logger log = LoggerFactory.getLogger(CaseCandidateService.class);

    private final CaseService caseService;
    private final MaterialService materialService;

    public CaseCandidateService(CaseService caseService, MaterialService materialService) {
        this.caseService = caseService;
        this.materialService = materialService;
    }

    @Transactional
    public CaseCandidateBatchResult createBatch(CaseCandidateBatchRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw BadRequestException.invalidParameter("items 不能为空：至少确认一条候选事项");
        }
        if (request.items().size() > CaseCandidateBatchRequest.MAX_ITEMS) {
            throw BadRequestException.invalidParameter(
                    "一次最多确认 " + CaseCandidateBatchRequest.MAX_ITEMS + " 条，当前 " + request.items().size() + " 条");
        }
        String materialId = request.materialId() == null ? "" : request.materialId().trim();
        if (materialId.isEmpty()) {
            throw BadRequestException.invalidParameter("materialId 不能为空");
        }
        MaterialEntity material = materialService.require(materialId);
        if (!material.isReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_NOT_READY",
                    "素材尚未就绪（当前状态：" + material.getStatus() + "），无法写入核验事项");
        }

        List<String> problems = validate(request.items());
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CASE_CANDIDATE_INVALID",
                    "有 " + problems.size() + " 条候选未通过校验，整批未写入：" + String.join("；", problems));
        }

        List<CaseDetail> created = new ArrayList<>();
        for (CaseCandidateBatchRequest.Item item : request.items()) {
            created.add(caseService.create(new CaseCreateRequest(
                    materialId,
                    item.question() == null ? null : item.question().trim(),
                    item.claim() == null ? null : item.claim().trim(),
                    item.startSec(),
                    item.endSec(),
                    null, null, null), "ai"));
        }
        log.info("候选事项入库：素材={} 条数={}", materialId, created.size());
        return new CaseCandidateBatchResult(materialId, request.items().size(), created.size(), created, "ai");
    }

    /** 收集所有条目的问题，索引从 1 开始（与界面上的「第 N 条」一致）。 */
    private static List<String> validate(List<CaseCandidateBatchRequest.Item> items) {
        List<String> problems = new ArrayList<>();
        BigDecimal max = CaseCreateRequest.MAX_SEC;
        for (int i = 0; i < items.size(); i++) {
            CaseCandidateBatchRequest.Item item = items.get(i);
            int no = i + 1;
            if (item == null) {
                problems.add("第 " + no + " 条为空");
                continue;
            }
            String question = item.question() == null ? "" : item.question().trim();
            String claim = item.claim() == null ? "" : item.claim().trim();
            if (question.isEmpty()) {
                problems.add("第 " + no + " 条：问题不能为空");
            } else if (question.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
                problems.add("第 " + no + " 条：问题超过 " + CaseCreateRequest.MAX_TITLE_LENGTH + " 字符");
            }
            if (claim.isEmpty()) {
                problems.add("第 " + no + " 条：承诺不能为空");
            } else if (claim.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
                problems.add("第 " + no + " 条：承诺超过 " + CaseCreateRequest.MAX_TITLE_LENGTH + " 字符");
            }
            BigDecimal start = item.startSec();
            BigDecimal end = item.endSec();
            if ((start == null) != (end == null)) {
                problems.add("第 " + no + " 条：开始秒与结束秒必须成对填写或都留空");
            }
            if (start != null && end != null) {
                if (start.compareTo(end) > 0) {
                    problems.add("第 " + no + " 条：开始秒不能大于结束秒");
                }
                if (start.signum() < 0 || end.compareTo(max) > 0) {
                    problems.add("第 " + no + " 条：时间区间超出 0~" + max.stripTrailingZeros().toPlainString() + " 秒");
                }
            }
        }
        return problems;
    }
}
