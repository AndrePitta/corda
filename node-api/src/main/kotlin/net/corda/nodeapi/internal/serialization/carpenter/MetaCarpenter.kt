package net.corda.nodeapi.internal.serialization.carpenter

import net.corda.nodeapi.internal.serialization.amqp.CompositeType
import net.corda.nodeapi.internal.serialization.amqp.TypeNotation

/**
 * Generated from an AMQP schema this class represents the classes unknown to the deserialiser and that thusly
 * require carpenting up in bytecode form. This is a multi step process as carpenting one object may be depedent
 * upon the creation of others, this information is tracked in the dependency tree represented by
 * [dependencies] and [dependsOn]. Creatable classes are stored in [carpenterSchemas].
 *
 * The state of this class after initial generation is expected to mutate as classes are built by the carpenter
 * enablaing the resolution of dependencies and thus new carpenter schemas added whilst those already
 * carpented schemas are removed.
 *
 * @property carpenterSchemas The list of carpentable classes
 * @property dependencies Maps a class to a list of classes that depend on it being built first
 * @property dependsOn Maps a class to a list of classes it depends on being built before it
 *
 * Once a class is constructed we can quickly check for resolution by first looking at all of its dependents in the
 * [dependencies] map. This will give us a list of classes that depended on that class being carpented. We can then
 * in turn look up all of those classes in the [dependsOn] list, remove their dependency on the newly created class,
 * and if that list is reduced to zero know we can now generate a [Schema] for them and carpent them up
 */
data class CarpenterSchemas (
        val carpenterSchemas: MutableList<Schema>,
        val dependencies: MutableMap<String, Pair<TypeNotation, MutableList<String>>>,
        val dependsOn: MutableMap<String, MutableList<String>>) {
    companion object CarpenterSchemaConstructor {
        fun newInstance(): CarpenterSchemas {
            return CarpenterSchemas(
                    mutableListOf<Schema>(),
                    mutableMapOf<String, Pair<TypeNotation, MutableList<String>>>(),
                    mutableMapOf<String, MutableList<String>>())
        }
    }

    fun addDepPair(type: TypeNotation, dependant: String, dependee: String) {
        dependsOn.computeIfAbsent(dependee, { mutableListOf<String>() }).add(dependant)
        dependencies.computeIfAbsent(dependant, { Pair(type, mutableListOf<String>()) }).second.add(dependee)
    }

    val size
        get() = carpenterSchemas.size

    fun isEmpty() = carpenterSchemas.isEmpty()
    fun isNotEmpty() = carpenterSchemas.isNotEmpty()
}

/**
 * Take a dependency tree of [CarpenterSchemas] and reduce it to zero by carpenting those classes that
 * require it. As classes are carpented check for depdency resolution, if now free generate a [Schema] for
 * that class and add it to the list of classes ([CarpenterSchemas.carpenterSchemas]) that require
 * carpenting
 *
 * @property cc a reference to the actual class carpenter we're using to constuct classes
 * @property objects a list of carpented classes loaded into the carpenters class loader
 */
abstract class MetaCarpenterBase (val schemas : CarpenterSchemas, val cc : ClassCarpenter = ClassCarpenter()) {
    val objects = mutableMapOf<String, Class<*>>()

    fun step(newObject: Schema) {
        objects[newObject.name] = cc.build (newObject)

        // go over the list of everything that had a dependency on the newly
        // carpented class existing and remove it from their dependency list, If that
        // list is now empty we have no impediment to carpenting that class up
        schemas.dependsOn.remove(newObject.name)?.forEach { dependent ->
            assert (newObject.name in schemas.dependencies[dependent]!!.second)

            schemas.dependencies[dependent]?.second?.remove(newObject.name)

            // we're out of blockers so  we can now create the type
            if (schemas.dependencies[dependent]?.second?.isEmpty() ?: false) {
                (schemas.dependencies.remove (dependent)?.first as CompositeType).carpenterSchema (
                        classLoaders = listOf<ClassLoader> (
                                ClassLoader.getSystemClassLoader(),
                                cc.classloader),
                        carpenterSchemas = schemas)
            }
        }
    }

    abstract fun build()

    val classloader : ClassLoader
            get() = cc.classloader
}

class MetaCarpenter(schemas : CarpenterSchemas,
                    cc : ClassCarpenter = ClassCarpenter()) : MetaCarpenterBase(schemas, cc) {
    override fun build() {
        while (schemas.carpenterSchemas.isNotEmpty()) {
            val newObject = schemas.carpenterSchemas.removeAt(0)
            step (newObject)
        }
    }
}

class TestMetaCarpenter(schemas : CarpenterSchemas,
                        cc : ClassCarpenter = ClassCarpenter()) : MetaCarpenterBase(schemas, cc) {
    override fun build() {
        if (schemas.carpenterSchemas.isEmpty()) return
        step (schemas.carpenterSchemas.removeAt(0))
    }
}

