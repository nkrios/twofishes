//  Copyright 2012 Foursquare Labs Inc. All Rights Reserved
package com.foursquare.twofishes

import com.foursquare.twofishes.Identity._
import com.foursquare.twofishes.util.{CountryUtils, GeoTools, StoredFeatureId, TwofishesLogger}
import com.foursquare.twofishes.util.Lists.Implicits._
import scala.collection.mutable.HashMap
import scalaj.collection.Implicits._

// Comparator for parses, we score by a number of different features
//

case class ScoringTerm(func: GeocodeParseOrdering.ScoringFunc, multiplier: Double = 1.0)
case class ScorerArguments(req: CommonGeocodeRequestParams, parse: Parse[Sorted], primaryFeature: GeocodeServingFeature, rest: Seq[FeatureMatch])

object GeocodeParseOrdering {
  type ScoringFunc = (ScorerArguments) => Option[(Int, String)]

  val scorersForGeocode: List[ScoringTerm] = List(
    ScoringTerm(populationBoost),
    ScoringTerm(penalizeRepeatedFeatures),
    ScoringTerm(promoteFeatureWithBounds),
    ScoringTerm(promoteWoeHintMatch),
    ScoringTerm(penalizeIrrelevantLanguageNameMatches),
    ScoringTerm(penalizeLongParses),
    ScoringTerm(promoteCountryHintMatch),
    //ScoringTerm(scaleRankBoost),
    ScoringTerm(distanceToBoundsOrLatLngHint),
    ScoringTerm(manualBoost),
    ScoringTerm(usTieBreak),
    ScoringTerm(penalizeCounties),
    ScoringTerm(woeTypeOrderForFeature),
    ScoringTerm(woeTypeOrderForParents)
  )

  val scorersForAutocomplete: List[ScoringTerm] = List(
    ScoringTerm(populationBoost, 0.1),
    ScoringTerm(penalizeRepeatedFeatures),
    ScoringTerm(promoteFeatureWithBounds),
    ScoringTerm(promoteWoeHintMatch),
    ScoringTerm(penalizeIrrelevantLanguageNameMatches),
    ScoringTerm(penalizeLongParses),
    ScoringTerm(promoteCountryHintMatch, 10.0),
    //ScoringTerm(scaleRankBoost),
    ScoringTerm(distanceToBoundsOrLatLngHint),
    ScoringTerm(manualBoost, 0.001),
    ScoringTerm(usTieBreak),
    ScoringTerm(penalizeCounties),
    ScoringTerm(woeTypeOrderForFeature),
    ScoringTerm(woeTypeOrderForParents),
    ScoringTerm(penalizeAirports)
  )

  def populationBoost(args: ScorerArguments): Option[(Int, String)] = {
    Some(args.primaryFeature.scoringFeatures.population, "population")
  }

  def penalizeRepeatedFeatures(args: ScorerArguments): Option[(Int, String)] = {
    // if we have a repeated feature, downweight this like crazy
    // so st petersburg, st petersburg works, but doesn't break new york, ny
    if (args.parse.hasDupeFeature) {
      Some((-100000000, "downweighting dupe-feature parse"))
    } else None
  }

  def promoteFeatureWithBounds(args: ScorerArguments): Option[(Int, String)] = {
    if (args.primaryFeature.feature.geometry.boundsOption.nonEmpty) {
      Some((1000, "promoting feature with bounds"))
    } else None
  }

  def promoteWoeHintMatch(args: ScorerArguments): Option[(Int, String)] = {
    if (args.req.woeHint.has(args.primaryFeature.feature.woeType)) {
      Some((50000000, "woe hint matches %d".format(args.primaryFeature.feature.woeType.getValue)))
    } else None
  }

  def penalizeIrrelevantLanguageNameMatches(args: ScorerArguments): Option[(Int, String)] = {
    val primaryMatchLangs = (for {
      fmatch <- args.parse.headOption.toList
      nameHit <- fmatch.possibleNameHits
      locale <- nameHit.langOption
      lang = locale.split("-")(0)
    } yield lang).toList

    if (primaryMatchLangs.has("en") ||
        primaryMatchLangs.has("abbr") ||
        primaryMatchLangs.has("iata") ||
        primaryMatchLangs.has("icao") ||
        primaryMatchLangs.has("") || // a lot of aliases tend to be names without a language
        args.req.langOption.exists(lang => primaryMatchLangs.has(lang)) ||
        primaryMatchLangs.exists(lang => CountryUtils.isLocalLanguageForCountry(args.primaryFeature.feature.cc, lang))) {
      None
    } else Some((-100000000, "penalizing  name match in irrelevant language"))
  }

  def penalizeLongParses(args: ScorerArguments): Option[(Int, String)] = {
    // prefer a more aggressive parse ... bleh
    // this prefers "mt laurel" over the town of "laurel" in "mt" (montana)
    Some((-5000 * args.parse.length, "parse length boost"))
  }

  def promoteCountryHintMatch(args: ScorerArguments): Option[(Int, String)] = {
    // Matching country hint is good
    if (args.req.ccOption.exists(_ == args.primaryFeature.feature.cc)) {
      if (args.primaryFeature.feature.woeType =? YahooWoeType.POSTAL_CODE) {
        Some((10000000, "postal code country code match"))
      } else {
        Some((1000000, "country code match"))
      }
    } else None
  }

  def scaleRankBoost(args: ScorerArguments): Option[(Int, String)] = {
    for {
      attributes <- args.primaryFeature.feature.attributesOption
      rank <- attributes.scalerankOption
      if rank > 0
    } yield {
        ((20 - rank) * 1000000, "exponential scale rank increase")
    }
  }

  def distanceToBoundsOrLatLngHint(args: ScorerArguments): Option[(Int, String)] = {
    def distanceBoostForPoint(ll: GeocodePoint): (Int, String) = {
      val distance = if (args.primaryFeature.feature.geometry.boundsOption.nonEmpty) {
        GeoTools.distanceFromPointToBounds(ll, args.primaryFeature.feature.geometry.boundsOrThrow)
      } else {
        GeoTools.getDistance(ll.lat, ll.lng,
          args.primaryFeature.feature.geometry.center.lat,
          args.primaryFeature.feature.geometry.center.lng)
      }

      val (bucketName, distanceBoost, woeTypeBoost) = if (distance < 5000) {
        ("<5km boost", 4000000, if (args.primaryFeature.feature.woeType =? YahooWoeType.SUBURB) 6000000 else 0)
      } else if (distance < 10000) {
        ("5-10km boost", 2000000, if (args.primaryFeature.feature.woeType =? YahooWoeType.SUBURB) 3000000 else 0)
      } else if (distance < 20000) {
        ("10-20km boost", 1000000, if (args.primaryFeature.feature.woeType =? YahooWoeType.SUBURB) 2000000 else 0)
      } else if (distance < 100000) {
        ("20-100km boost", -10000, 0)
      } else {
        (">=100km penalty", -100000, 0)
      }

      val debugString = if (args.req.debug > 0) {
        val woeTypeBoostString = if (woeTypeBoost > 0) {
          " (BONUS %s for woeType=%s)".format(woeTypeBoost, args.primaryFeature.feature.woeType.stringValue)
        } else {
          ""
        }
        "%s : %s for being %s meters away.%s".format(
          bucketName,
          distanceBoost,
          distance.toString,
          woeTypeBoostString)
      } else {
        ""
      }
      (distanceBoost + woeTypeBoost, debugString)
    }

    val llHint = args.req.llHintOption
    val boundsHint = args.req.boundsOption
    if (boundsHint.isDefined) {
      boundsHint.flatMap(bounds => {
        // if you're in the bounds and the bounds are some small enough size
        // you get a uniform boost
        val bbox = GeoTools.boundingBoxToS2Rect(bounds)
        // distance in meters of the hypotenuse
        // if it's smaller than looking at 1/4 of new york state, then
        // boost everything in it by a lot
        val bboxContainsCenter =
          GeoTools.boundsContains(bounds, args.primaryFeature.feature.geometry.center)
        val bboxesIntersect =
          args.primaryFeature.feature.geometry.boundsOption.map(fBounds =>
            GeoTools.boundsIntersect(bounds, fBounds)).getOrElse(false)

        if (bbox.lo().getEarthDistance(bbox.hi()) < 200 * 1000 &&
          (bboxContainsCenter || bboxesIntersect)) {
          if (args.primaryFeature.feature.woeType =? YahooWoeType.SUBURB) {
            Some((5000000, "200km bbox neighborhood intersection BONUS"))
          } else {
            Some((2000000, "200km bbox intersection BONUS"))
          }
        } else {
          // fall back to basic distance-from-center logic
          Some(distanceBoostForPoint(GeoTools.S2LatLngToPoint(bbox.getCenter)))
        }
      })
    } else {
      // Penalize far-away things
      llHint.flatMap(ll =>
        Some(distanceBoostForPoint(ll))
      )
    }
  }

  def manualBoost(args: ScorerArguments): Option[(Int, String)] = {
    // manual boost added at indexing time
    if (args.primaryFeature.scoringFeatures.boost != 0) {
      Some((args.primaryFeature.scoringFeatures.boost, "manual boost"))
    } else None
  }

  def usTieBreak(args: ScorerArguments): Option[(Int, String)] = {
    // as a terrible tie break, things in the US > elsewhere
    // meant primarily for zipcodes
    if (args.primaryFeature.feature.cc == "US") {
      Some((1, "US tie-break"))
    } else None
  }

  def penalizeCounties(args: ScorerArguments): Option[(Int, String)] = {
    // no one likes counties
    if (args.primaryFeature.feature.cc == "US" && args.primaryFeature.feature.woeType == YahooWoeType.ADMIN2) {
      Some((-30000, "no one likes counties in the US"))
    } else None
  }

  def woeTypeOrderForFeature(args: ScorerArguments): Option[(Int, String)] = {
    Some((-1 * YahooWoeTypes.getOrdering(args.primaryFeature.feature.woeType), "prefer smaller interpretation"))
  }

  def woeTypeOrderForParents(args: ScorerArguments): Option[(Int, String)] = {
    // In autocomplete mode, prefer "tighter" interpretations
    // That is, prefer "<b>Rego Park</b>, <b>N</b>Y" to
    // <b>Rego Park</b>, NY, <b>N</b>aalagaaffeqatigiit
    //
    // getOrdering returns a smaller # for a smaller thing
    val parentTypes = args.rest.map(_.fmatch.feature.woeType).sortBy(YahooWoeTypes.getOrdering)
    if (parentTypes.nonEmpty) {
      Some((-1 * YahooWoeTypes.getOrdering(parentTypes(0)), "prefer smaller parent interpretation"))
    } else None
  }

  def penalizeAirports(args: ScorerArguments): Option[(Int, String)] = {
    if (args.primaryFeature.feature.woeType == YahooWoeType.AIRPORT) {
      Some((-50000000, "downweight airports in autocomplete"))
    } else {
      None
    }
  }
}

class GeocodeParseOrdering(
    store: GeocodeStorageReadService,
    req: CommonGeocodeRequestParams,
    logger: TwofishesLogger,
    scorers: List[ScoringTerm] = Nil
  ) extends Ordering[Parse[Sorted]] {
  // Higher is better
  def scoreParse(parse: Parse[Sorted]): Int = {
    parse.headOption.map(primaryFeatureMatch => {
      val primaryFeature = primaryFeatureMatch.fmatch
      val rest = parse.drop(1)
      var signal = 0

      def modifySignal(value: Int, debug: String) {
        if (req.debug > 0) {
          logger.ifDebug(" -- %s: %s + %s = %s", debug, signal, value, signal + value)
          parse.addDebugLine(
            DebugScoreComponent(debug, value)
          )
        }
        signal += value
      }

      if (req.debug > 0) {
        logger.ifDebug("Scoring %s", parse)
      }

      StoredFeatureId.fromLong(primaryFeature.longId).foreach(fid =>
        store.hotfixesBoosts.get(fid).foreach(boost =>
          modifySignal(boost, "hotfix boost"))
      )

      for {
        scorer <- scorers
        (value, debugStr) <- scorer.func(ScorerArguments(req, parse, primaryFeature, rest))
      } {
        modifySignal((value * scorer.multiplier).toInt, debugStr)
      }

      if (req.debug > 0) {
        logger.ifDebug("final score %s", signal)
        parse.setFinalScore(signal)
      }
      signal
    }).getOrElse(0)
  }

  var scoreMap = new scala.collection.mutable.HashMap[String, Int]
  def getScore(p: Parse[Sorted]): Int = {
    val scoreKey = p.scoreKey
    if (!scoreMap.contains(scoreKey)) {
      scoreMap(scoreKey) = scoreParse(p)
    }

    scoreMap.getOrElse(scoreKey, -1)
  }

  def normalCompare(a: Parse[Sorted], b: Parse[Sorted]): Int = {
    val scoreA = getScore(a)
    val scoreB = getScore(b)
    if (scoreA == scoreB) {
      val diff = (a.headOption.map(_.fmatch.feature.longId).getOrElse(0L) -
        b.headOption.map(_.fmatch.feature.longId).getOrElse(0L))
      // .signum is slow, we don't want the .toInt to cause weird
      // long wrapping issues, so manually do this.
      if (diff < 0) { -1 }
      else if (diff > 0) { 1 }
      else { 0 }
    } else {
      scoreB - scoreA
    }
  }

  def compare(a: Parse[Sorted], b: Parse[Sorted]): Int = {
    // logger.ifDebug("Scoring %s vs %s".format(printDebugParse(a), printDebugParse(b)))

    val aFeature = a.primaryFeature
    val bFeature = b.primaryFeature

    if (aFeature.tokenStart == bFeature.tokenStart &&
        aFeature.tokenEnd == bFeature.tokenEnd &&
        aFeature.fmatch.feature.woeType != YahooWoeType.COUNTRY &&
        bFeature.fmatch.feature.woeType != YahooWoeType.COUNTRY &&
        // if we have a hint that we want one of the types, then let the
        // scoring happen naturally
        !req.woeHint.has(aFeature.fmatch.feature.woeType) &&
        !req.woeHint.has(bFeature.fmatch.feature.woeType)
    ) {

      // if b is a parent of a, prefer a
      if (aFeature.fmatch.scoringFeatures.parentIds.has(bFeature.fmatch.longId) &&
        (aFeature.fmatch.scoringFeatures.population * 1.0 / bFeature.fmatch.scoringFeatures.population) > 0.05
      ) {
        logger.ifDebug("Preferring %s because it's a child of %s", a, b)
        -1
      // if a is a parent of b, prefer b
      } else if (bFeature.fmatch.scoringFeatures.parentIds.has(aFeature.fmatch.longId) &&
         (bFeature.fmatch.scoringFeatures.population * 1.0 / aFeature.fmatch.scoringFeatures.population) > 0.05
        ) {
        logger.ifDebug("Preferring %s because it's a child of %s", b, a)
        1
      } else {
        normalCompare(a, b)
      }
    } else {
      normalCompare(a, b)
    }
  }
}
