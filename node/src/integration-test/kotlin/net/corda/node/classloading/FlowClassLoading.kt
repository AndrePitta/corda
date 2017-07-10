package net.corda.node.classloading

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.getOrThrow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import net.corda.node.internal.classloading.CordappLoader
import net.corda.testing.node.InMemoryMessagingNetwork
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.node.InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin
import org.junit.Assert
import org.junit.Test

class FlowClassLoading {
    private val mockNet = MockNetwork(servicePeerAllocationStrategy = RoundRobin())
    private val loader = CordappLoader.createDevMode("net.corda.node.classloading")

    private data class Data(val value: Int)

    private inner class Initiator(private val otherSide: Party) : FlowLogic<Data>() {
        override fun call(): Data {
            Assert.assertEquals(loader.appClassLoader, javaClass.classLoader)
            send(otherSide, Data(0))
            val data: Data = receive<Data>(otherSide).unwrap { it }
            Assert.assertEquals(loader.appClassLoader, data.javaClass.classLoader)
            return data
        }
    }

    @InitiatedBy(Initiator::class)
    private inner class Receiver(val otherSide: Party) : FlowLogic<Unit>() {
        override fun call() {
            Assert.assertEquals(loader.appClassLoader, javaClass.classLoader)
            val data = receive<Data>(otherSide).unwrap { it }
            Assert.assertEquals(loader.appClassLoader, data.javaClass.classLoader)
            send(otherSide, data)
        }
    }

    @Test
    fun `flows from Cordapps use the correct classloader`() {
        val (nodeA, nodeB) = mockNet.createTwoNodes()
        val data = nodeA.services.startFlow(Initiator(nodeB.info.legalIdentity)).resultFuture.getOrThrow()
        Assert.assertEquals(loader.appClassLoader, data.javaClass.classLoader)
    }
}