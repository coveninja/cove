package com.coveninja.cove.backend.nuvio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graalvm.polyglot.Context

class NuvioAndroidJavaScriptTest {
    @Test
    fun transpiledPromiseProviderCompletesAgainstTheBlockingFetchFacade() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", TEST_BRIDGE)
            context.eval("js", androidNuvioBootstrap())
            context.eval("js", synchronousNuvioScraperSource(TRANSPILED_PROVIDER))
            context.eval("js", ANDROID_NUVIO_INVOKE_SCRIPT)

            assertTrue(context.eval("js", "globalThis.__coveDone === true").asBoolean())
            assertEquals("", context.eval("js", "String(globalThis.__coveError)").asString())
            assertEquals(
                "https://video.example/movie",
                context.eval("js", "JSON.parse(globalThis.__coveResult)[0].url").asString(),
            )
            assertEquals(
                "application/json",
                context.eval("js", "globalThis.__observedContentType").asString(),
            )
            // The bootstrap used to carry cheerio and crypto-js inline, so every invocation paid
            // to parse a quarter of a megabyte a provider like this one never touches.
            assertEquals(
                0,
                context.eval("js", "globalThis.__moduleSourceRequests.length").asInt(),
            )
        }
    }

    @Test
    fun literalAsyncProviderStillWorksAfterSynchronousSourceNormalization() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", TEST_BRIDGE)
            context.eval("js", androidNuvioBootstrap())
            context.eval("js", synchronousNuvioScraperSource(LITERAL_ASYNC_PROVIDER))
            context.eval("js", ANDROID_NUVIO_INVOKE_SCRIPT)

            assertTrue(context.eval("js", "globalThis.__coveDone === true").asBoolean())
            assertEquals("", context.eval("js", "String(globalThis.__coveError)").asString())
            assertEquals(
                "https://video.example/movie",
                context.eval("js", "JSON.parse(globalThis.__coveResult)[0].url").asString(),
            )
        }
    }

    @Test
    fun officialRuntimeGlobalsAliasesAndGlobalExportAreAvailable() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", TEST_BRIDGE)
            context.eval("js", androidNuvioBootstrap())
            context.eval("js", GLOBAL_PROVIDER)
            context.eval("js", ANDROID_NUVIO_INVOKE_SCRIPT)

            assertTrue(context.eval("js", "globalThis.__coveDone === true").asBoolean())
            assertEquals("", context.eval("js", "String(globalThis.__coveError)").asString())
            assertEquals(
                "https://streamtape.com:8443/get_video?token=1&quality=1080p#watch",
                context.eval("js", "JSON.parse(globalThis.__coveResult)[0].url").asString(),
            )
            assertEquals(
                "cheerio:movie-42",
                context.eval("js", "JSON.parse(globalThis.__coveResult)[0].name").asString(),
            )
            // Required on demand rather than pre-parsed, and only the alias target is asked for.
            assertEquals(
                listOf("cheerio-without-node-native"),
                context.eval("js", "JSON.stringify(globalThis.__moduleSourceRequests)")
                    .asString()
                    .let(::parseNames),
            )
        }
    }

    private fun parseNames(json: String): List<String> =
        json.removeSurrounding("[", "]").split(",").filter(String::isNotBlank).map { it.trim('"') }

    private companion object {
        val TEST_BRIDGE = """
            globalThis.__moduleSourceRequests = [];
            globalThis.__bridge = {
              log() {},
              base64Encode(value) { return String(value); },
              base64Decode(value) { return String(value); },
              moduleSource(name) {
                globalThis.__moduleSourceRequests.push(name);
                if (name === 'cheerio-without-node-native') {
                  return 'module.exports = {marker: "cheerio"};';
                }
                return '';
              },
              request(url, options) {
                const parsedOptions = JSON.parse(options);
                if (parsedOptions.headers.Connection !== 'keep-alive') {
                  throw new Error('request options were not preserved');
                }
                return JSON.stringify({
                  status: 200,
                  statusText: 'OK',
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({name: '1080p', url: 'https://video.example/movie'}),
                  url,
                  redirected: false
                });
              }
            };
            globalThis.__invocationHost = {
              json() {
                return '{"tmdbId":42,"mediaType":"movie","title":"Movie","year":2026,"imdbId":"tt42"}';
              }
            };
        """.trimIndent()

        val TRANSPILED_PROVIDER = """
            var __async = (__this, __arguments, generator) => {
              return new Promise((resolve, reject) => {
                var fulfilled = value => {
                  try { step(generator.next(value)); }
                  catch (error) { reject(error); }
                };
                var rejected = value => {
                  try { step(generator.throw(value)); }
                  catch (error) { reject(error); }
                };
                var step = result => result.done
                  ? resolve(result.value)
                  : Promise.resolve(result.value).then(fulfilled, rejected);
                step((generator = generator.apply(__this, __arguments)).next());
              });
            };

            function getStreams() {
              return __async(this, null, function* () {
                if (global !== globalThis || window !== globalThis) throw new Error('global aliases missing');
                const query = new URLSearchParams({title: 'Cove test'});
                let timerRan = false;
                yield new Promise(resolve => setTimeout(() => { timerRan = true; resolve(); }, 10));
                if (!timerRan || query.toString() !== 'title=Cove+test') {
                  throw new Error('browser compatibility globals missing');
                }
                const response = yield fetch('https://example.test/streams', {
                  headers: {Connection: 'keep-alive'}
                });
                globalThis.__observedContentType = response.headers.get('content-type');
                const stream = yield response.json();
                return Promise.all([stream]);
              });
            }
            module.exports = {getStreams};
        """.trimIndent()

        val LITERAL_ASYNC_PROVIDER = """
            module.exports.getStreams = async () => {
              const response = await fetch('https://example.test/streams', {
                headers: {Connection: 'keep-alive'}
              });
              const stream = await response.json();
              return [stream];
            };
        """.trimIndent()

        val GLOBAL_PROVIDER = """
            globalThis.getStreams = function(id, type) {
              if (self !== globalThis) throw new Error('self alias missing');
              const controller = new AbortController();
              let abortObserved = false;
              controller.signal.addEventListener('abort', () => { abortObserved = true; });
              controller.abort('test');
              if (!controller.signal.aborted || !abortObserved || controller.signal.reason !== 'test') {
                throw new Error('abort globals are incomplete');
              }
              const cheerio = require('cheerio');
              if (cheerio !== require('react-native-cheerio')) throw new Error('module aliases diverged');
              const url = new URL('/get_video?token=1#watch', 'https://old.example/path/page');
              url.hostname = 'streamtape.com';
              url.port = '8443';
              url.searchParams.set('quality', '1080p');
              const finish = () => [{name: cheerio.marker + ':' + type + '-' + id, url: url.toString()}];
              const rejectAbortedFetch = error => {
                if (!error || error.name !== 'AbortError') throw new Error('aborted fetch was not rejected');
                return finish();
              };
              try {
                return Promise.resolve(fetch('https://must-not-fetch.example', {signal: controller.signal})).then(
                  () => { throw new Error('aborted fetch reached the bridge'); },
                  rejectAbortedFetch
                );
              } catch (error) {
                return rejectAbortedFetch(error);
              }
            };
        """.trimIndent()
    }
}
