package io.beangate.usage;

/** Seam for the metering pipeline: logging now, bounded queue + batch insert in W3. */
public interface UsageRecorder {

    void record(UsageRecord record);
}
