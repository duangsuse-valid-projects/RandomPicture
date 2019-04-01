package org.duangsuse.fushion

import sun.misc.Signal
import sun.misc.SignalHandler

abstract class ServiceSignalHandler(val sigSet: Set<Signal>): SignalHandler {
    internal lateinit var parent: SignalHandler

    override fun handle(sig: Signal) {
        if (sig in sigSet) {
            sigAction(sig)
            return
        }

        parent.handle(sig)
    }

    abstract fun sigAction(sig: Signal): Unit

    companion object Helper {
        fun install(signame: String, k: Class<out ServiceSignalHandler>): ServiceSignalHandler = install(signame, k.newInstance())

        fun install(signame: String, o: ServiceSignalHandler): ServiceSignalHandler {
            val sigInstance = signame.let(::Signal)
            o.parent = Signal.handle(sigInstance, o) // Sun sighandler chaining

            return o
        }
    }
}
