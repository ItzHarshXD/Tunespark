package com.tunespark.music.rss

/**
 * Centralized RSS feed configuration.
 *
 * All feed URLs for every interest category are defined here so new sources
 * can be added or existing ones changed easily without touching any other code.
 *
 * Each interest maps to multiple high-quality, actively maintained public RSS feeds
 * to ensure diversity and reliability. If one feed fails, the others still load.
 */
object RssConfig {

    /** Cache duration for fetched articles (25 minutes). */
    const val CACHE_DURATION_MS = 25 * 60 * 1000L

    /** Maximum number of articles to keep per feed after parsing. */
    const val MAX_ARTICLES_PER_FEED = 20

    /** Maximum number of articles to show on the Home screen daily discover carousel. */
    const val HOME_DISCOVER_LIMIT = 10

    /** Placeholder image used when an article has no thumbnail. */
    const val PLACEHOLDER_IMAGE =
        "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400&auto=format&fit=crop"

    /**
     * A single RSS source definition.
     *
     * @param name      Display name of the source (shown in the UI).
     * @param url       The RSS feed URL.
     * @param category  The interest category this feed belongs to.
     */
    data class RssSource(
        val name: String,
        val url: String,
        val category: String
    )

    /**
     * All available RSS sources grouped by interest category.
     *
     * The keys match the interest labels used in [com.tunespark.music.SessionManager.getDiscoverCategories].
     * Each category aggregates from multiple high-quality feeds.
     */
    val SOURCES_BY_CATEGORY: Map<String, List<RssSource>> = mapOf(
        "🤖 AI" to listOf(
            RssSource("MIT Technology Review - AI", "https://www.technologyreview.com/topic/artificial-intelligence/feed", "🤖 AI"),
            RssSource("VentureBeat - AI", "https://venturebeat.com/category/ai/feed/", "🤖 AI"),
            RssSource("The Verge - AI", "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml", "🤖 AI"),
            RssSource("Google AI Blog", "https://blog.google/technology/ai/rss/", "🤖 AI"),
            RssSource("OpenAI Blog", "https://openai.com/blog/rss.xml", "🤖 AI"),
            RssSource("AI News", "https://www.artificialintelligence-news.com/feed/", "🤖 AI")
        ),
        "💻 Tech" to listOf(
            RssSource("The Verge", "https://www.theverge.com/rss/index.xml", "💻 Tech"),
            RssSource("TechCrunch", "https://techcrunch.com/feed/", "💻 Tech"),
            RssSource("Wired", "https://www.wired.com/feed/rss", "💻 Tech"),
            RssSource("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "💻 Tech"),
            RssSource("Engadget", "https://www.engadget.com/rss.xml", "💻 Tech"),
            RssSource("ZDNet", "https://www.zdnet.com/news/rss.xml", "💻 Tech")
        ),
        "🚀 Space" to listOf(
            RssSource("NASA Breaking News", "https://www.nasa.gov/news-release/feed/", "🚀 Space"),
            RssSource("Space.com", "https://www.space.com/feeds/all", "🚀 Space"),
            RssSource("SpaceNews", "https://spacenews.com/feed/", "🚀 Space"),
            RssSource("Universe Today", "https://www.universetoday.com/feed/", "🚀 Space"),
            RssSource("ESA News", "https://www.esa.int/rssfeed/Our_Activities", "🚀 Space"),
            RssSource("SpaceFlight Now", "https://spaceflightnow.com/feed/", "🚀 Space")
        ),
        "🔬 Science" to listOf(
            RssSource("ScienceDaily", "https://www.sciencedaily.com/rss/all.xml", "🔬 Science"),
            RssSource("Nature News", "https://www.nature.com/nature.rss", "🔬 Science"),
            RssSource("New Scientist", "https://www.newscientist.com/feed/home", "🔬 Science"),
            RssSource("Live Science", "https://www.livescience.com/feeds/all", "🔬 Science"),
            RssSource("Phys.org", "https://phys.org/rss-feed/", "🔬 Science"),
            RssSource("Science News", "https://www.sciencenews.org/feed", "🔬 Science")
        ),
        "🚗 Cars & EVs" to listOf(
            RssSource("Electrek", "https://electrek.co/feed/", "🚗 Cars & EVs"),
            RssSource("Car and Driver", "https://www.caranddriver.com/rss/all.xml/", "🚗 Cars & EVs"),
            RssSource("InsideEVs", "https://insideevs.com/rss/news/", "🚗 Cars & EVs"),
            RssSource("Autoblog", "https://www.autoblog.com/rss.xml", "🚗 Cars & EVs"),
            RssSource("MotorTrend", "https://www.motortrend.com/feed/", "🚗 Cars & EVs"),
            RssSource("Top Gear", "https://www.topgear.com/rss/news", "🚗 Cars & EVs")
        ),
        "🎮 Gaming" to listOf(
            RssSource("IGN", "https://feeds.ign.com/ign/all", "🎮 Gaming"),
            RssSource("Polygon", "https://www.polygon.com/rss/index.xml", "🎮 Gaming"),
            RssSource("Kotaku", "https://kotaku.com/rss", "🎮 Gaming"),
            RssSource("GameSpot", "https://www.gamespot.com/feeds/mashup/", "🎮 Gaming"),
            RssSource("PC Gamer", "https://www.pcgamer.com/rss/", "🎮 Gaming"),
            RssSource("Eurogamer", "https://www.eurogamer.net/feed", "🎮 Gaming")
        ),
        "🎬 Movies & TV" to listOf(
            RssSource("Variety", "https://variety.com/feed/", "🎬 Movies & TV"),
            RssSource("The Hollywood Reporter", "https://www.hollywoodreporter.com/feed/", "🎬 Movies & TV"),
            RssSource("Deadline", "https://deadline.com/feed/", "🎬 Movies & TV"),
            RssSource("Screen Rant", "https://screenrant.com/feed/", "🎬 Movies & TV"),
            RssSource("Collider", "https://collider.com/feed/", "🎬 Movies & TV"),
            RssSource("IndieWire", "https://www.indiewire.com/feed/", "🎬 Movies & TV")
        ),
        "💼 Business & Startups" to listOf(
            RssSource("TechCrunch - Startups", "https://techcrunch.com/category/startups/feed/", "💼 Business & Startups"),
            RssSource("Business Insider", "https://www.businessinsider.com/rss", "💼 Business & Startups"),
            RssSource("Fast Company", "https://www.fastcompany.com/rss", "💼 Business & Startups"),
            RssSource("Inc.com", "https://www.inc.com/rss", "💼 Business & Startups"),
            RssSource("Forbes", "https://www.forbes.com/business/feed/", "💼 Business & Startups"),
            RssSource("Entrepreneur", "https://www.entrepreneur.com/latest.rss", "💼 Business & Startups")
        ),
        "💰 Finance" to listOf(
            RssSource("CNBC Finance", "https://www.cnbc.com/id/10000664/device/rss/rss.html", "💰 Finance"),
            RssSource("MarketWatch", "https://feeds.marketwatch.com/marketwatch/topstories/", "💰 Finance"),
            RssSource("Bloomberg", "https://feeds.bloomberg.com/markets/news.rss", "💰 Finance"),
            RssSource("Yahoo Finance", "https://finance.yahoo.com/news/rssindex", "💰 Finance"),
            RssSource("Investing.com", "https://www.investing.com/rss/news.rss", "💰 Finance"),
            RssSource("The Motley Fool", "https://www.fool.com/feed/", "💰 Finance")
        ),
        "🧠 Mind & Productivity" to listOf(
            RssSource("Lifehacker", "https://lifehacker.com/rss", "🧠 Mind & Productivity"),
            RssSource("Psychology Today", "https://www.psychologytoday.com/us/front/feed", "🧠 Mind & Productivity"),
            RssSource("Zen Habits", "https://zenhabits.net/feed/", "🧠 Mind & Productivity"),
            RssSource("James Clear", "https://jamesclear.com/feed", "🧠 Mind & Productivity"),
            RssSource("Medium - Productivity", "https://medium.com/feed/tag/productivity", "🧠 Mind & Productivity"),
            RssSource("99U", "https://www.adobe.com/creativecloud/discover/feed.html", "🧠 Mind & Productivity")
        ),
        "🌍 World" to listOf(
            RssSource("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "🌍 World"),
            RssSource("CNN World", "http://rss.cnn.com/rss/edition_world.rss", "🌍 World"),
            RssSource("Reuters World", "https://feeds.reuters.com/reuters/worldNews", "🌍 World"),
            RssSource("The Guardian World", "https://www.theguardian.com/world/rss", "🌍 World"),
            RssSource("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "🌍 World"),
            RssSource("NPR World", "https://feeds.npr.org/1004/rss.xml", "🌍 World")
        ),
        "🎵 Music" to listOf(
            RssSource("Pitchfork", "https://pitchfork.com/feed/feed-news/rss", "🎵 Music"),
            RssSource("Rolling Stone", "https://www.rollingstone.com/music/feed/", "🎵 Music"),
            RssSource("Billboard", "https://www.billboard.com/feed/", "🎵 Music"),
            RssSource("NME", "https://www.nme.com/news/music/feed", "🎵 Music"),
            RssSource("Stereogum", "https://www.stereogum.com/feed/", "🎵 Music"),
            RssSource("Consequence of Sound", "https://consequence.net/feed/", "🎵 Music")
        ),
        "⚽ Sports" to listOf(
            RssSource("ESPN", "https://www.espn.com/espn/rss/news", "⚽ Sports"),
            RssSource("BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml", "⚽ Sports"),
            RssSource("Sky Sports", "https://www.skysports.com/rss/12040", "⚽ Sports"),
            RssSource("The Athletic", "https://theathletic.com/feed/", "⚽ Sports"),
            RssSource("CBS Sports", "https://www.cbssports.com/rss/headlines/", "⚽ Sports"),
            RssSource("Yahoo Sports", "https://sports.yahoo.com/rss/", "⚽ Sports")
        ),
        "👗 Fashion" to listOf(
            RssSource("Vogue", "https://www.vogue.com/rss", "👗 Fashion"),
            RssSource("Hypebeast", "https://hypebeast.com/feed", "👗 Fashion"),
            RssSource("GQ", "https://www.gq.com/rss", "👗 Fashion"),
            RssSource("Elle", "https://www.elle.com/rss/all.xml/", "👗 Fashion"),
            RssSource("Harper's Bazaar", "https://www.harpersbazaar.com/rss/all.xml/", "👗 Fashion"),
            RssSource("WWD", "https://wwd.com/feed/", "👗 Fashion")
        ),
        "🍳 Food" to listOf(
            RssSource("Serious Eats", "https://www.seriouseats.com/rss", "🍳 Food"),
            RssSource("Bon Appétit", "https://www.bonappetit.com/feed/rss", "🍳 Food"),
            RssSource("Food & Wine", "https://www.foodandwine.com/rss/all.xml", "🍳 Food"),
            RssSource("Eater", "https://www.eater.com/rss/index.xml", "🍳 Food"),
            RssSource("Tasting Table", "https://www.tastingtable.com/feed/", "🍳 Food"),
            RssSource("Delish", "https://www.delish.com/rss/all.xml/", "🍳 Food")
        ),
        "✈️ Travel" to listOf(
            RssSource("Lonely Planet", "https://www.lonelyplanet.com/news/feed", "✈️ Travel"),
            RssSource("Condé Nast Traveler", "https://www.cntraveler.com/rss", "✈️ Travel"),
            RssSource("Travel + Leisure", "https://www.travelandleisure.com/rss/all.xml", "✈️ Travel"),
            RssSource("Nomadic Matt", "https://www.nomadicmatt.com/travel-blog/feed/", "✈️ Travel"),
            RssSource("Atlas Obscura", "https://www.atlasobscura.com/feeds/latest", "✈️ Travel"),
            RssSource("National Geographic Travel", "https://www.nationalgeographic.com/travel/feed", "✈️ Travel")
        )
    )

    /**
     * Returns all RSS sources for the given list of enabled interest categories.
     * If no categories are enabled, returns all sources as a fallback.
     */
    fun getSourcesForCategories(enabledCategories: List<String>): List<RssSource> {
        val selected = enabledCategories.filter { it.isNotBlank() }
        if (selected.isEmpty()) {
            return SOURCES_BY_CATEGORY.values.flatten()
        }
        return selected.flatMap { category ->
            SOURCES_BY_CATEGORY[category].orEmpty()
        }
    }
}