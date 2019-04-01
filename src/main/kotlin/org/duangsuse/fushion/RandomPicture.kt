package org.duangsuse.fushion

import java.io.*
import java.util.*

import kotlin.random.Random

import io.vertx.core.*
import io.vertx.ext.web.*

import io.vertx.core.http.*
import io.vertx.core.logging.*

//// Typedefs
typealias Path = String

//// Helper functions
fun <A, B> Future<A>.catching(chain: Future<B>): Future<A> = this.setHandler { if (this.succeeded()) chain.complete() else chain.fail(this.cause()) }
fun <T> List<T>.indexedSample(): Pair<Int, T> = Random.nextInt(this.size).let { Pair(it, this[it]) }

val String.java get() = this as java.lang.String
fun java.lang.String.toUrlBase64(): String = Base64.getUrlEncoder().encodeToString(this.getBytes())

fun HttpServerResponse.putHeader(h: CharSequence, o: Number) = run { putHeader(h, o.toString()) }

fun HttpServerResponse.contentTypeUtf8() = run { putHeader(HttpHeaders.CONTENT_TYPE, "text/plain;charset=utf8") }
fun HttpServerResponse.writeEnd(body: String) = run { putHeader(HttpHeaders.CONTENT_LENGTH, body.java.getBytes().size); write(body); end() }

/**
 * Random picture service
 */
class RandomPicture: AbstractVerticle() {
    private val pictures: MutableList<Path> = mutableListOf()

    private lateinit var server: HttpServer

    private val logger = LoggerFactory.getLogger(this.javaClass.name)


    /**
     * Application start logic:
     *
     * + starts image index function (checkPathEnvironment, indexImages)
     * + starts server (drawRoutes, createHttpServer, listen)
     */
    override fun start(startFuture: Future<Void>) {
        val imagePath = checkImagePath(startFuture)
        logger.info("Checking with regex /$ACCEPTABLE_REGEX/")

        val indexImagePromise = Future.future<Any> {
            imagePath?.run {
                withTimeLog("indexing images") {
                    indexImage(imagePath)
                    " size ${pictures.size}"
                }
                it.complete()
                return@future
            }
            it.fail("Failed checking images dir!")
        }

        val startServerPromise = Future.future<HttpServer> {
            fut ->
            
            val router = drawRoutes()
            server = createHttpServer(router)

            val listenPort = Helper.env(ENV_PORT)?.toInt() ?: PORT_DEF
            server.listen(listenPort, fut)

            println("Server started at http://localhost:${server.actualPort()}")
        }.catching(startFuture)

        CompositeFuture.all(indexImagePromise, startServerPromise)
    }

    //// Serveice HTTP Server abstraction
    private fun createHttpServer(router: Router): HttpServer = vertx.createHttpServer().requestHandler(router)

    fun randomImage(): Pair<Int, Path> = pictures.indexedSample()

    private fun drawRoutes(): Router {
        val router = Router.router(vertx)

        router.route(HttpMethod.GET, IMAGE_SERVICE_PATH).handler {
            val (index, path) = randomImage()
            it.response()
                .setStatusCode(200)
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .putHeader(INDEX_HEADER, index.toString())
                .putHeader(FILENAME_HEADER, path.java.toUrlBase64())
                .sendFile(path)
        }

        /// External
        router.get(IMAGE_LIST_SERVICE_PATH).handler {
            it.response()
                .contentTypeUtf8()
                .writeEnd(pictures.indices.zip(pictures).joinToString("\n"))
        }
        router.get(INDEX_SERVICE_PATH).handler {
            val index = Helper.httpIdParamOrNull(it)
            index?.let(pictures::getOrNull)?.run {
                it.response()
                    .putHeader(INDEX_HEADER, index.toString())
                    .sendFile(this)

                return@handler
            }
            Helper.badIndexResponse(it.response())
        }
        router.get(INDEX_NAME_SERIVCE_PATH).handler {
            val index = Helper.httpIdParamOrNull(it)
            index?.let(pictures::getOrNull)?.run {
                it.response()
                    .contentTypeUtf8()
                    .writeEnd(this)
                return@handler
            }
            Helper.badIndexResponse(it.response())
        }

        return router
    }

    //// Application random image path
    fun checkImagePath(parentPromise: Future<*>?): Path? {
        val v = Helper.env(ENV_DIR) ?: "/app/favourite"
        val f = File(v)

        if (!f.exists() or f.isFile) {
            parentPromise?.fail("Input path $v ($ENV_DIR) should be exists and is folder")
            return null
        }

        return f.path
    }

    fun indexImage(path: Path) {
        val indexImageLogging = { msg: String -> { f: File -> withTimeLog(msg, " (${f.name})"); true } }

        val seq = path.let(::File).walk()
            .onEnter(indexImageLogging(">"))
            .onFail { f, _ -> indexImageLogging("!!")(f) }
            .onLeave { d -> indexImageLogging("<")(d) }

        fun logIsImageFile(f: File): Boolean {
            if (Helper.isImageFile(f)) return true
            return false.also { logger.warn("Ignoring non-picture file ${f.path}") }
        }

        val (accepted, ignored) = seq.partition(::logIsImageFile)

        accepted.map(File::getAbsolutePath).forEach { x -> pictures.add(x) }

        logger.warn("${ignored.size} files ignored, ${pictures.size} files added")
    }

    private fun withTimeLog(name: String, desc: String) = withTimeLog(name) { desc }
    private fun withTimeLog(name: String, op: () -> String?) {
        val started = Helper.timeTicks()
        logger.info("Begin $name")
        val desc = op() ?: ""
        val ended = Helper.timeTicks()
        val duration = ended - started
        logger.info("Finished $name$desc costs $duration ms")
    }

    companion object Constants {
        const val PORT_DEF = 8080
        const val ENV_DIR = "RANDOM_PICTURE"
        const val ENV_PORT = "PORT"
        const val IMAGE_SERVICE_PATH = "/"

        const val IMAGE_LIST_SERVICE_PATH = "/list"
        const val INDEX_SERVICE_PATH = "/:id"
        const val INDEX_NAME_SERIVCE_PATH = "/:id/name"

        const val BAD_INDEX_MESSAGE = "Index not found or not integer"

        const val FILENAME_HEADER = "Image-File-Name"
        const val INDEX_HEADER = "Image-No"

        private const val ACCEPTABLE_EXTENSIONS = "png,jpg,jpeg,gif,webp,raw,bmp,img,svg"
        val ACCEPTABLE_REGEX = ACCEPTABLE_EXTENSIONS.split(',').joinToString("|").let { "^.*\\.($it)$" }.let(::Regex)
    }

    object Helper {
        internal fun env(name: String): String? = System.getenv(name)
        internal fun isImageFile(f: File) = ACCEPTABLE_REGEX.matches(f.name)
        internal fun timeTicks(): Long = System.currentTimeMillis()
        internal fun badIndexResponse(stream: HttpServerResponse) = stream.setStatusCode(400).writeEnd(BAD_INDEX_MESSAGE)
        internal fun httpIdParamOrNull(endpoint: RoutingContext): Int? = endpoint.request().getParam("id").toIntOrNull();
    }
}

