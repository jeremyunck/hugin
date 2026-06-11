package com.example.agent.scheduler;

/**
 * SPI for delivering the result of a scheduled prompt back to whoever requested it.
 *
 * <p>Kept transport-agnostic so {@code agent-core} never depends on a delivery channel. The runnable
 * backend server provides the concrete implementation — an outbox that fans the
 * result out over SSE to subscribed clients (e.g. the Discord bot, which posts it to the originating
 * channel or DM).
 */
public interface ScheduledResultDelivery {

    /**
     * Delivers a finished scheduled run.
     *
     * @param target the origin session id captured when the prompt was scheduled
     *               (e.g. {@code discord-channel-123}); used to route the result back
     * @param prompt the prompt that was run (for context in the delivered message)
     * @param result the agent's answer
     */
    void deliver(String target, String prompt, String result);
}
