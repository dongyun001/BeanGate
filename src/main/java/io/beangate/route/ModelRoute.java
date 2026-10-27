package io.beangate.route;

import io.beangate.provider.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One ordered route from a public model name to an upstream provider.
 *
 * <p>Deliberately a table rather than a single {@code fallback_provider_id} column: the fallback
 * provider usually uses a different upstream model name, and a priority-ordered list makes both
 * multi-level failover and later capability-aware routing a data change instead of a schema change.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "model_route", uniqueConstraints = {
        @UniqueConstraint(name = "uk_route_model_provider", columnNames = {"public_model", "provider_id"})
})
public class ModelRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The model name callers use, e.g. {@code deepseek-chat}. */
    @Column(name = "public_model", nullable = false, length = 128)
    private String publicModel;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    /** The model name the provider itself expects, e.g. {@code deepseek-chat} or {@code qwen2.5:0.5b}. */
    @Column(name = "upstream_model", nullable = false, length = 128)
    private String upstreamModel;

    /** Lower value is tried first. */
    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;
}
