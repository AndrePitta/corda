package net.corda.core.messaging

import com.google.common.util.concurrent.ListenableFuture
import net.corda.core.ErrorOr
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UpgradedContract
import net.corda.core.crypto.Party
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowInitiator
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.node.services.StateMachineTransactionMapping
import net.corda.core.node.services.Vault
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import org.bouncycastle.asn1.x500.X500Name
import rx.Observable
import java.io.InputStream
import java.security.PublicKey
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@CordaSerializable
data class StateMachineInfo(
        val id: StateMachineRunId,
        val flowLogicClassName: String,
        val initiator: FlowInitiator,
        val progressTrackerStepAndUpdates: Pair<String, Observable<String>>?
) {
    override fun toString(): String = "${javaClass.simpleName}($id, $flowLogicClassName, $initiator)"
}

@CordaSerializable
sealed class StateMachineUpdate {
    abstract val id: StateMachineRunId

    data class Added(val stateMachineInfo: StateMachineInfo) : StateMachineUpdate() {
        override val id: StateMachineRunId get() = stateMachineInfo.id
    }

    data class Removed(override val id: StateMachineRunId, val result: ErrorOr<*>) : StateMachineUpdate()
}

/**
 *
 */
@CordaSerializable
open class SnapshotWithUpdates<out S, U>(val snapshot: List<S>, rawUpdates: Observable<U>) : AutoCloseable {
    private val subscribers = AtomicInteger(-1)
    val updates: Observable<U> = rawUpdates
            .doOnSubscribe { if (!subscribers.compareAndSet(-1, 1)) subscribers.incrementAndGet() }
            .doOnUnsubscribe { subscribers.decrementAndGet() }

    @Deprecated("Use snapshot instead", ReplaceWith("snapshot"))
    val first: List<S> get() = snapshot
    @Deprecated("Use updates instead", ReplaceWith("updates"))
    val second: Observable<U> get() = updates

    operator fun component1(): List<S> = snapshot
    operator fun component2(): Observable<U> = updates

    override fun close() {
        val subCount = subscribers.get()
        if (subCount == -1) {
            // No one has used the Observable but we still need to do this subscribe-unsubscribe dance to tell the
            // server to stop buffering updates
            updates.subscribe({}, {}).unsubscribe()
        } else if (subCount > 0) {
            throw IllegalStateException("There are $subCount subscriptions left open")
        }
    }

    override fun toString(): String = "${javaClass.simpleName}(snapshot=$snapshot)"
}

class SameType<T>(snapshot: List<T>, updates: Observable<T>) : SnapshotWithUpdates<T, T>(snapshot, updates) {
    fun everything(): Observable<T> = updates.startWith(snapshot)
}

/**
 * RPC operations that the node exposes to clients using the Java client library. These can be called from
 * client apps.
 */
interface CordaRPCOps : RPCOps {
    /**
     * Returns the RPC protocol version, which is the same the node's Platform Version. Exists since version 1 so guaranteed
     * to be present.
     */
    override val protocolVersion: Int get() = nodeIdentity().platformVersion

    @RPCReturnsObservables
    @Deprecated("", ReplaceWith("flowStateMachines()"))
    fun stateMachinesAndUpdates(): Pair<List<StateMachineInfo>, Observable<StateMachineUpdate>> {
        val (snapshot, updates) = flowStateMachines()
        return snapshot to updates
    }

    /**
     * Returns a pair of currently in-progress flow state machine infos and an observable of future state machine adds and
     * removes.
     */
    fun flowStateMachines(): SnapshotWithUpdates<StateMachineInfo, StateMachineUpdate>

    /**
     * Returns a pair of head states in the vault and an observable of future updates to the vault.
     */
    @RPCReturnsObservables
    fun vaultAndUpdates(): SnapshotWithUpdates<StateAndRef<ContractState>, Vault.Update>

    /**
     * Returns a pair of all recorded transactions and an observable of future recorded ones.
     */
    @RPCReturnsObservables
    fun verifiedTransactions(): SameType<SignedTransaction>

    /**
     * Returns a snapshot list of existing state machine id - recorded transaction hash mappings, and a stream of future
     * such mappings as well.
     */
    @RPCReturnsObservables
    fun stateMachineRecordedTransactionMapping(): SameType<StateMachineTransactionMapping>

    /**
     * Returns all parties currently visible on the network with their advertised services and an observable of future updates to the network.
     */
    @RPCReturnsObservables
    @Deprecated("", ReplaceWith("networkMap()"))
    fun networkMapUpdates(): Pair<List<NodeInfo>, Observable<NetworkMapCache.MapChange>> {
        val (snapshot, updates) = networkMap()
        return snapshot to updates
    }

    fun networkMap(): SnapshotWithUpdates<NodeInfo, NetworkMapCache.MapChange>

    /**
     * Start the given flow with the given arguments.
     */
    @RPCReturnsObservables
    fun <T : Any> startFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?): FlowHandle<T>

    /**
     * Start the given flow with the given arguments, returning an [Observable] with a single observation of the
     * result of running the flow.
     */
    @RPCReturnsObservables
    fun <T : Any> startTrackedFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?): FlowProgressHandle<T>

    /**
     * Returns Node's identity, assuming this will not change while the node is running.
     */
    fun nodeIdentity(): NodeInfo

    /*
     * Add note(s) to an existing Vault transaction
     */
    fun addVaultTransactionNote(txnId: SecureHash, txnNote: String)

    /*
     * Retrieve existing note(s) for a given Vault transaction
     */
    fun getVaultTransactionNotes(txnId: SecureHash): Iterable<String>

    /*
     * Returns a map of how much cash we have in each currency, ignoring details like issuer. Note: currencies for
     * which we have no cash evaluate to null (not present in map), not 0.
     */
    fun getCashBalances(): Map<Currency, Amount<Currency>>

    /**
     * Checks whether an attachment with the given hash is stored on the node.
     */
    fun attachmentExists(id: SecureHash): Boolean

    /**
     * Download an attachment JAR which was previously uploaded using [uploadAttachment] using its ID.
     */
    fun downloadAttachment(id: SecureHash): InputStream

    @Deprecated("Use downloadAttachment instead", ReplaceWith("downloadAttachment"))
    fun openAttachment(id: SecureHash): InputStream = downloadAttachment(id)

    /**
     * Uploads a jar to the node, returns it's hash.
     */
    fun uploadAttachment(jar: InputStream): SecureHash

    // TODO: Remove this from the interface
    @Deprecated("This service will be removed in a future milestone")
    fun uploadFile(dataType: String, name: String?, file: InputStream): String

    /**
     * Authorise a contract state upgrade.
     * This will store the upgrade authorisation in the vault, and will be queried by [ContractUpgradeFlow.Acceptor] during contract upgrade process.
     * Invoking this method indicate the node is willing to upgrade the [state] using the [upgradedContractClass].
     * This method will NOT initiate the upgrade process. To start the upgrade process, see [ContractUpgradeFlow.Instigator].
     */
    fun authoriseContractUpgrade(state: StateAndRef<*>, upgradedContractClass: Class<out UpgradedContract<*, *>>)

    /**
     * Authorise a contract state upgrade.
     * This will remove the upgrade authorisation from the vault.
     */
    fun deauthoriseContractUpgrade(state: StateAndRef<*>)

    /**
     * Returns the node's current time.
     */
    fun currentNodeTime(): Instant

    /**
     * Returns a [ListenableFuture] which completes when the node has registered wih the network map service. It can also
     * complete with an exception if it is unable to.
     */
    @RPCReturnsObservables
    fun waitUntilRegisteredWithNetworkMap(): ListenableFuture<*>

    // TODO These need rethinking. Instead of these direct calls we should have a way of replicating a subset of
    // the node's state locally and query that directly.
    /**
     * Returns the [Party] corresponding to the given key, if found.
     */
    fun partyFromKey(key: PublicKey): Party?

    /**
     * Returns the [Party] with the given name as it's [Party.name]
     */
    fun partyFromName(name: String): Party?

    /**
     * Returns the [Party] with the X.500 principal as it's [Party.name]
     */
    fun partyFromX500Name(x500Name: X500Name): Party?

    /** Enumerates the class names of the flows that this node knows about. */
    fun registeredFlows(): List<String>
}

/**
 * These allow type safe invocations of flows from Kotlin, e.g.:
 *
 * val rpc: CordaRPCOps = (..)
 * rpc.startFlow(::ResolveTransactionsFlow, setOf<SecureHash>(), aliceIdentity)
 *
 * Note that the passed in constructor function is only used for unification of other type parameters and reification of
 * the Class instance of the flow. This could be changed to use the constructor function directly.
 */
inline fun <T : Any, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: () -> R
): FlowHandle<T> = startFlowDynamic(R::class.java)

inline fun <T : Any, A, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A) -> R,
        arg0: A
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0)

inline fun <T : Any, A, B, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B) -> R,
        arg0: A,
        arg1: B
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1)

inline fun <T : Any, A, B, C, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C) -> R,
        arg0: A,
        arg1: B,
        arg2: C
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2)

inline fun <T : Any, A, B, C, D, reified R : FlowLogic<T>> CordaRPCOps.startFlow(
        @Suppress("UNUSED_PARAMETER")
        flowConstructor: (A, B, C, D) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D
): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2, arg3)

/**
 * Same again, except this time with progress-tracking enabled.
 */
@Suppress("unused")
inline fun <T : Any, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: () -> R
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java)

@Suppress("unused")
inline fun <T : Any, A, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A) -> R,
        arg0: A
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0)

@Suppress("unused")
inline fun <T : Any, A, B, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B) -> R,
        arg0: A,
        arg1: B
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1)

@Suppress("unused")
inline fun <T : Any, A, B, C, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C) -> R,
        arg0: A,
        arg1: B,
        arg2: C
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2)

@Suppress("unused")
inline fun <T : Any, A, B, C, D, reified R : FlowLogic<T>> CordaRPCOps.startTrackedFlow(
        @Suppress("unused_parameter")
        flowConstructor: (A, B, C, D) -> R,
        arg0: A,
        arg1: B,
        arg2: C,
        arg3: D
): FlowProgressHandle<T> = startTrackedFlowDynamic(R::class.java, arg0, arg1, arg2, arg3)
