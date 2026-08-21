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
            context.eval("js", androidNuvioBootstrap(""))
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
        }
    }

    @Test
    fun literalAsyncProviderStillWorksAfterSynchronousSourceNormalization() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", TEST_BRIDGE)
            context.eval("js", androidNuvioBootstrap(""))
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

    private companion object {
        val TEST_BRIDGE = """
            globalThis.__bridge = {
              log() {},
              base64Encode(value) { return String(value); },
              base64Decode(value) { return String(value); },
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
    }
}
