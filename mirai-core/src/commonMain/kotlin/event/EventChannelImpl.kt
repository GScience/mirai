/*
 * Copyright 2019-2022 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

package net.mamoe.mirai.internal.event

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.withLock
import net.mamoe.mirai.event.*
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.internal.network.Packet
import net.mamoe.mirai.internal.network.components.SHOW_VERBOSE_EVENT
import net.mamoe.mirai.utils.MiraiLogger
import net.mamoe.mirai.utils.verbose
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

// You probably should only use EventChannelToEventDispatcherAdapter.instance, or just use EventDispatchers. Event.broadcast is also good to use internally!
@RequiresOptIn(
    "Every EventChannelImpl has dedicated EventListeners registries. Use the constructor only when you know what you are doing.",
    level = RequiresOptIn.Level.ERROR
)
internal annotation class DangerousEventChannelImplConstructor

internal open class EventChannelImpl<E : Event> @DangerousEventChannelImplConstructor constructor(
    baseEventClass: KClass<out E>, defaultCoroutineContext: CoroutineContext = EmptyCoroutineContext
) : EventChannel<E>(baseEventClass, defaultCoroutineContext) {
    val eventListeners = EventListeners()

    // drop any unsubscribed events
    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    companion object {
        private val logger by lazy { MiraiLogger.Factory.create(EventChannelImpl::class, "EventChannelImpl") }
    }

    suspend fun callListeners(event: Event) {
        event as AbstractEvent
        eventListeners.callListeners(event)
        flow.emit(event)
    }

    override fun asFlow(): Flow<E> {
        @Suppress("UNCHECKED_CAST")
        return flow.asSharedFlow().filter { baseEventClass.isInstance(it) } as Flow<E>
    }

    override fun <E : Event> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
        eventListeners.addListener(eventClass, listener)
    }

    override fun <E : Event> createListener(
        coroutineContext: CoroutineContext,
        concurrencyKind: ConcurrencyKind,
        priority: EventPriority,
        listenerBlock: suspend (E) -> ListeningStatus
    ): Listener<E> {
        val context = this.defaultCoroutineContext + coroutineContext
        return SafeListener(
            parentJob = context[Job],
            subscriberContext = context,
            listenerBlock = listenerBlock,
            concurrencyKind = concurrencyKind,
            priority = priority
        )
    }


    private suspend fun <E : Event> broadcastImpl(event: E): E {
        check(event is AbstractEvent) { "Events must extend AbstractEvent" }

        if (event is BroadcastControllable && !event.shouldBroadcast) {
            return event
        }
        event.broadCastLock.withLock {
            event._intercepted = false
            @Suppress("DEPRECATION")
            if (EventDisabled) return@withLock
            logEvent(event)
            eventListeners.callListeners(event)
        }

        return event
    }

    private fun isVerboseEvent(event: Event): Boolean {
        if (SHOW_VERBOSE_EVENT) return false
        if (event is VerboseEvent) {
            if (event is BotEvent) {
                return !event.bot.configuration.isShowingVerboseEventLog
            }
            return true
        }
        return false
    }

    private fun logEvent(event: Event) {
        if (event is Packet.NoEventLog) return
        if (event is Packet.NoLog) return
        if (event is MessageEvent) return // specially handled in [LoggingPacketHandlerAdapter]
//        if (this is Packet) return@withLock // all [Packet]s are logged in [LoggingPacketHandlerAdapter]
        if (isVerboseEvent(event)) return

        if (event is BotEvent) {
            event.bot.logger.verbose { "Event: $event" }
        } else {
            logger.verbose { "Event: $event" }
        }
    }

    override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<E> {
        val newDefaultContext = coroutineContexts.fold(defaultCoroutineContext) { acc, coroutineContext ->
            acc + coroutineContext
        }

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        return object : DelegateEventChannel<E>(this) {
            override fun <E : Event> createListener(
                coroutineContext: CoroutineContext,
                concurrencyKind: ConcurrencyKind,
                priority: EventPriority,
                listenerBlock: suspend (E) -> ListeningStatus
            ): Listener<E> {
                return super.createListener(
                    newDefaultContext + coroutineContext,
                    concurrencyKind,
                    priority,
                    listenerBlock
                )
            }

            override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<E> {
                return delegate.context(newDefaultContext, *coroutineContexts)
            }
        }
    }
}


internal abstract class DelegateEventChannel<BaseEvent : Event>(
    protected val delegate: EventChannel<BaseEvent>,
) : EventChannel<BaseEvent>(delegate.baseEventClass, delegate.defaultCoroutineContext) {
    override fun asFlow(): Flow<BaseEvent> = delegate.asFlow()

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    override fun <E : Event> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
        delegate.registerListener0(eventClass, listener)
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    override fun <E : Event> createListener(
        coroutineContext: CoroutineContext,
        concurrencyKind: ConcurrencyKind,
        priority: EventPriority,
        listenerBlock: suspend (E) -> ListeningStatus
    ): Listener<E> = delegate.createListener0(coroutineContext, concurrencyKind, priority, listenerBlock)

    override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<BaseEvent> {
        return delegate.context(*coroutineContexts)
    }
}
