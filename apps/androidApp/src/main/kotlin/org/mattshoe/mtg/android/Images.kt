package org.mattshoe.mtg.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okhttp3.OkHttpClient

/**
 * Card art needs a loader, and the loader needs to be told it may use
 * the network.
 *
 * Coil finds its network fetcher through a ServiceLoader, which is the
 * kind of thing that works until a release build shrinks it away.
 * Naming it here costs three lines and cannot silently stop working —
 * and a grid of Magic cards with no pictures is not the same screen.
 */
class Images : Application(), SingletonImageLoader.Factory {

    /**
     * Scryfall's image CDN answers 400 to OkHttp's default
     * `okhttp/4.x` user agent. It wants to know who is asking, which is
     * fair, and the failure is silent — a grey rectangle where a card
     * should be, with nothing in the log unless Coil is made to talk.
     */
    private val scryfallFriendly by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "image/*")
                        .build(),
                )
            }
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { scryfallFriendly }))
                // The mana symbols are SVGs, and card art is not. Both
                // go through this loader.
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

/** Who is asking, for Scryfall's benefit. */
const val USER_AGENT = "mtg-collection/1.0 (+https://mtg.mattshoe.org)"
