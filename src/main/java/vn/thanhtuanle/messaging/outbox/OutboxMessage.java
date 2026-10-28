package vn.thanhtuanle.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** A Kafka record waiting in {@code t_outbox} until {@link OutboxRelay} has sent it. */
@Entity
@Table(name = "t_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "message_key")
    private String messageKey;

    /** The record value exactly as it goes on the wire (JSON bytes). */
    @Column(nullable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Null until the broker acknowledged the record. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** The relay instance sending this row owns it until then; null when nobody does. */
    @Column(name = "claimed_until")
    private LocalDateTime claimedUntil;

    /** How many times the broker refused the record for good; a failure that may heal is not counted. */
    @Column(nullable = false)
    private int attempts;

    /** Set when {@link #attempts} reached the limit: the row is never sent again. */
    @Column(name = "dead_at")
    private LocalDateTime deadAt;
}
