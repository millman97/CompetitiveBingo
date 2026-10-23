package com.millman97.competitivebingo.model;

/**
 * Marker for the type-specific {@code detail} object on an {@link EventEnvelope}.
 *
 * Gson serialises by runtime type; the server tells the shapes apart by {@code metric.type}.
 */
public interface EventDetail
{
}
