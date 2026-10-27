package io.beangate.route;

import io.beangate.provider.Provider;

/** A concrete attempt: which provider to call and which upstream model name to use. */
public record ResolvedRoute(Provider provider, String upstreamModel) {
}
