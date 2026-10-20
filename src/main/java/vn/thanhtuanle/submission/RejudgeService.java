package vn.thanhtuanle.submission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Judges finished submissions again against the problem's current test data and limits. Like a submit, a rejudge
 * reserves its queue slot first and gives it back if anything after fails; the judge spec is fetched outside any
 * transaction, and only the reset and its judge request in the outbox are one transaction. The reset is conditional
 * (never over PENDING or JUDGING), so a submission is never queued twice by concurrent rejudges.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RejudgeService {

    private final SubmissionRepository submissionRepository;
    private final JudgeService judgeService;
    private final ProblemCatalog problemCatalog;
    private final SubmissionMapper submissionMapper;
    private final OutboxWriter outboxWriter;
    private final SubmissionAdmission admission;
    private final TransactionOperations transactions;

    public SubmissionResponseDto rejudge(UUID id) {
        Submission submission = submissionRepository.findWithLanguageById(id)
                .orElseThrow(() -> notFound(id));
        if (!SubmissionResult.isTerminal(submission.getStatus())) {
            throw new AppException(ErrorCode.SUBMISSION_NOT_FINISHED);
        }

        admission.admit(1);
        try {
            JudgeSpec spec = problemCatalog.judgeSpec(submission.getProblemSlug());
            String sourceCode = submission.getSourceCode();
            Language language = submission.getLanguage();
            transactions.executeWithoutResult(status -> {
                if (submissionRepository.resetForRejudge(id, LocalDateTime.now()) == 0) {
                    throw new AppException(ErrorCode.SUBMISSION_NOT_FINISHED);
                }
                queue(id, sourceCode, spec, language);
            });
        } catch (RuntimeException | Error e) {
            admission.release(1);
            throw e;
        }
        log.info("Submission {} queued for a rejudge", id);

        return submissionRepository.findWithLanguageById(id)
                .map(submissionMapper::toDto)
                .orElseThrow(() -> notFound(id));
    }

    /** Appends the judge request; the outbox relay sends it once the caller's transaction has committed. */
    private void queue(UUID id, String sourceCode, JudgeSpec spec, Language language) {
        SubmissionRequestedEvent event = judgeService.buildRequestedEvent(id.toString(), sourceCode, spec, language);
        outboxWriter.append(KafkaTopics.SUBMISSION_REQUESTED, event.getSubmissionId(), event);
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Submission not found with id: " + id);
    }
}
