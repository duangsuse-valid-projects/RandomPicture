package org.duangsuse.fushion

import java.io.*
import kotlin.random.Random

import io.vertx.core.*
import io.vertx.ext.web.*

import io.vertx.core.http.*
import io.vertx.core.logging.*

typealias Path = String

fun Future<*>.catch(chain: Future<*>) = if (this.succeeded()) chain.complete() else chain.fail(this.cause())

/**
 * Random picture service
 */
class RandomPicture: AbstractVerticle() {
    private val pictures: MutableList<Path> = mutableListOf()
    private val server: HttpServer = vertx.createHttpServer()

    private val logger = LoggerFactory.getLogger(this.javaClass.name)

    override fun start(startFuture: Future<Void>) {
        val imagePath = envImagePath(startFuture)

        val indexImagePromise = Future.future<Any> {
            withTimeLog("Indexing images") {
                indexImage(imagePath)
                " size ${pictures.size}"
            }
            it.complete()
        }

        val startServerPromise = Future.future<HttpServer> {
            fut ->
            val router = setupRouter()
            server.requestHandler(router)
            server.listen(PORT_DEF, fut)
        }.catch(startFuture)
    }

    private fun setupRouter(): Router {
        val router = Router.router(vertx)

        router.route(HttpMethod.GET, IMAGE_SERVICE_PATH).handler {
            it.response()
                .setStatusCode(200)
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .sendFile(randomImage())
        }

        return router
    }

    fun envImagePath(future: Future<*>?): Path {
        val v = System.getenv(ENV_DIR) ?: "/app/favourite"
        val f = File(v)

        if (!f.exists() or f.isFile) {
            future?.fail("Failed to get image file path")
            throw IOException("Bad input dir $v ($ENV_DIR) is not exists or not folder")
        }

        return f.path
    }

    fun indexImage(path: Path) {
        val indexImageLogging: (String) -> (File) -> Boolean = { i -> { f -> withTimeLog(i, " (${f.name})"); true } }
        fun logIsImageFile(f: File): Boolean {
            if (isImageFile(f)) return true
            return false.also { logger.info("Ignoring non-picture file ${f.path}") }
        }
        val seq = path.let(::File).walk()
            .onEnter(indexImageLogging(">"))
            .onFail { f, _ -> indexImageLogging("!!")(f) }
            .onLeave { d -> indexImageLogging("<")(d) }
        seq.filter(::logIsImageFile)
           .map(File::getName).forEach { x -> pictures.add(x) }
    }

    fun randomImage(): Path = pictures[Random.nextInt(pictures.size)]

    private fun withTimeLog(name: String, desc: String) = withTimeLog(name) { desc }
    private fun withTimeLog(name: String, op: () -> String?) {
        val started = System.currentTimeMillis()
        logger.info("Begin doing $name at $started")
        val desc = op() ?: ""
        val ended = System.currentTimeMillis()
        val duration = ended - started
        logger.info("Finished doing $name$desc at $ended costs $duration")
    }

    companion object Constants {
        const val PORT_DEF = 8080
        const val ENV_DIR = "RANDOM_PICTURE"
        const val IMAGE_SERVICE_PATH = "/"

        private const val ACCEPTABLE_EXTENSIONS = "png,jpg,jpeg,gif,webp"

        val ACCEPTABLE_REGEX = ACCEPTABLE_EXTENSIONS.split(',').fold(StringBuilder()) { ac, x -> ac.append("|\\.").append(x) }.let { "($it)" }.let(::Regex)

        private fun isImageFile(f: File) = ACCEPTABLE_REGEX.matches(f.name)
    }
}

