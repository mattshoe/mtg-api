package org.mattshoe.mtg.core

/**
 * Everything the hand-written web app does, enumerated, with a flag for
 * whether the multiplatform build does it yet.
 *
 * This exists because "port the whole app" is the kind of job that ends
 * with three screens quietly missing and nobody noticing until someone
 * needs one. A list in a commit message does not stop that. A test that
 * fails while anything here is `done = false` does.
 *
 * The rule: a feature flips to `done` only when there is a test
 * exercising it on both platforms. Flipping it because the code exists is
 * how the list becomes a lie.
 *
 * Taken from the routes in `app.js` and the exported surface of every
 * module in `frontend/js`, at 5,682 lines across 19 modules.
 */
enum class Area(val label: String) {
    LIBRARY("Library"),
    DECKS("Decks"),
    STATS("Stats"),
    QUERY("Query console"),
    ENTRY("Mass entry"),
    LOGS("Server logs"),
    CARD("Card detail"),
    SHELL("Shell and navigation"),
    ADMIN("Admin"),
    SHARE("Android share"),
}

data class Feature(
    val area: Area,
    /** What it does, in the words a person would use. */
    val what: String,
    /** Where it lives today, so the port has something to read. */
    val source: String,
    /**
     * The rules are in the shared core with tests on both targets.
     * Necessary for `done` and nowhere near sufficient — a search that
     * builds the right SQL is not a screen anyone can use.
     */
    val logic: Boolean = false,
    /** Usable end to end on Android and on the web. The only flag that counts. */
    val done: Boolean = false,
    /**
     * The tests that prove it, by function name.
     *
     * Naming them is the point: "there are eight hundred tests" is not
     * the same claim as "this feature has one". `CoverageGateTest`
     * checks every name here exists in a test source, so renaming a
     * test tells you which feature just lost its proof instead of
     * quietly leaving the inventory lying.
     */
    val tests: List<String> = emptyList(),
) {
    init {
        require(!done || logic) { "$what claims to be done without its logic ported" }
    }
}

/**
 * The manifest. Adding a row is how a gap gets recorded; flipping `done`
 * is how it gets closed.
 */
object Inventory {

    val features: List<Feature> = listOf(
        // ---------------------------------------------------------- shell
        Feature(Area.SHELL, "Hash routing between the six views", "app.js", logic = true, done = true,
            tests = listOf(
                "aRouteParses", "anUnknownViewIsTheDefault", "aRouteRoundTrips",
                "theOldAddAndRemoveNamesLandOnTheWizard", "theViewsAndWhichOfThemAreGated",
                "everyViewIsReachableUnlocked", "switchingTabsSwapsTheScreen", "everyUnlockedTabRendersItsOwnScreen",
            )),
        Feature(Area.SHELL, "Nav tabs, with the admin group hidden until unlocked", "app.js, index.html", logic = true, done = true,
            tests = listOf(
                "lockedHidesTheAdminViewsEntirely", "unlockedShowsThemAll", "noViewIsLostBetweenTheNavAndTheRouter",
                "lockedShowsFourTabsAndNoAdminOnes", "theCurrentTabIsMarked", "gatedTabsAreAbsentWhileLocked",
                "unlockingBringsTheGatedTabsBack",
                "theNavDoesNotBorrowTheClassTheHeadersOwnScriptOpens",
                "itIsOneHamburgerAndNothingElse", "theHamburgerOpensAndClosesTheMenu",
                "aPressAnywhereElseClosesIt", "pickingTheTabYouAreAlreadyOnStillClosesTheMenu",
                "theAdminHalfIsItsOwnSectionWithTheLockInIt",
                "theGatedViewsSitUnderTheRuleAndTheOthersAbove",
                "theMenuStaysOnScreenAtPhoneWidth", "thereIsNoFindButton",
                "theHeaderSaysWhatYouAreLookingAt", "andAnOpenDeckPutsItsOwnNameThere",
                "theMarkIsTheSameSizeAsTheHamburgerBesideIt",
            )),
        Feature(Area.ADMIN, "A retried write applies once, not twice",
            "api.js", logic = true, done = true,
            tests = listOf(
                "aKeyIsThirtyTwoHexCharacters", "everyKeyIsItsOwn",
                "everyWriteCarriesOne", "aReadCarriesNone",
            )),
        Feature(Area.CARD, "A new screen opens at the top, and back returns to where you were",
            "app.js", logic = true, done = true,
            tests = listOf(
                "somewhereNewStartsAtTheTop", "comingBackGoesBackToWhereYouWere",
                "aScreenNeverVisitedStartsAtTheTop", "theOffsetIsChasedUntilTheRowsArrive",
                "aSecondRequestWinsOverTheOneStillChasing",
                "aDeckOpensAtTheTopOfItself", "andBackReturnsToWhereTheListWas",
                "thePagesOwnBackButtonLandsWhereTheBrowsersDoes",
            )),
        Feature(Area.CARD, "Share a link to the card or the deck you are looking at",
            "card.js", logic = true, done = true,
            tests = listOf(
                "aLinkIsTheWholeAddressNotJustTheFragment",
                "theLinkToACardIsTheCardAndNothingElse",
                "theTitleIsTheCardWhenOneIsOpenAndThePageWhenNot",
                "theCardPageOffersAShare", "anOpenDeckOffersAShare",
            )),
        Feature(Area.DECKS, "Share a deck as a link or as its list, to clipboard or file",
            "decks.js", logic = true, done = true,
            tests = listOf(
                "theShareOffersALinkOrTheListEitherWay", "eachOfTheFourReportsItself",
                "aPressOutsideShutsTheShareMenu", "theShareMenuStaysOnScreenAtPhoneWidth",
                "theShareMenuReadsDownItsLeftEdge",
                "aDeckLeadsWithItsCommander", "aDeckWithNoCommanderHasNoBlankLineAtTheTop",
                "twoCommandersBothLead", "aDeckExportPastesBackIntoTheEntryBox",
                "anEmptyDeckExportsNothing", "theDeckFilenameIsTheDeckAndTheDate",
            )),
        Feature(Area.DECKS, "A token links to the token, not to the card it shares a name with",
            "decks.js", logic = true, done = true,
            tests = listOf(
                "aProductPageIsAlreadyExact", "aSearchIsToldToListTokensOnly",
                "theFilterGoesInsideTheWrappedAddressNotBesideIt",
                "aLinkWithMoreAfterTheAddressKeepsIt", "aBareSearchGetsThePlainFacet",
                "narrowingTwiceChangesNothing", "nothingIsStillNothing",
                "aSearchLinkIsNarrowedToTokensBeforeItLeaves",
            )),
        Feature(Area.ADMIN, "Every call retries a flaky network before it gives up",
            "api.js", logic = true, done = true,
            tests = listOf(
                "theFirstWaitIsThreeHundredMillisecondsAndEachDoubles",
                "thereAreFiveRetriesAfterTheFirstTry",
                "anAttemptNumberBelowOneIsStillTheFirstWait",
                "aServerErrorAndATooManyRequestsAreWorthAnotherTry",
                "aBadRequestIsNotWorthATry", "aCancelledRequestIsNotRetried",
                "aCallThatFailsTwiceStillComesBackWithAnAnswer",
                "aCallThatNeverWorksGivesUpAfterSixTries",
                "aRefusalIsReportedAtOnceRatherThanRetriedForNineSeconds",
            )),
        Feature(Area.SHELL, "Keyboard shortcuts and the ? help toast", "app.js", logic = true, done = true,
            tests = listOf(
                "lettersGoToTheirView", "gatedShortcutsAreAsHiddenAsTheirTabs", "nothingFiresWhileTyping",
                "theHelpToastListsOnlyWhatIsReachable", "questionMarkToasts", "aBareLetterNavigates",
                "theSameLetterInATextFieldDoesNot", "theHelpKeyToasts",
            )),
        // The website reaches it by ⌘K and `/`. A phone has neither, so
        // Android keeps the button the web nav lost.
        Feature(Area.SHELL, "Quick find palette on ⌘K and /", "app.js", logic = true, done = true,
            tests = listOf(
                "theFirstRowIsChosenUntilYouMove", "theHighlightStopsAtBothEnds", "itLooksAtBothFacesAndBothOwners",
                "theTermIsBoundNotPasted", "slashOpensTheFinder", "theChordWorksWhileTyping",
                "theFinderListsWhatWasFound", "theFindButtonOpensTheFinder",
            )),
        Feature(Area.SHELL, "Back button dismisses overlays instead of navigating", "overlay.js", logic = true, done = true,
            tests = listOf(
                "backTakesTheTopOneOff", "backWithNothingOpenIsNotHandled",
                "openingTheSameOverlayTwiceDoesNotStackIt", "closingAnOverlayThrowsAwayWhatItWasHolding",
                "closingOneUnderneathLeavesTheTopAlone", "navigatingTakesEveryOverlayWithIt",
                "escapeClosesWhateverIsOnTop", "escapeWithNothingOpenIsLeftAlone",
            )),
        Feature(Area.SHELL, "A palette a colourblind person can actually read",
            "app.css", logic = true, done = true,
            tests = listOf(
                "noTwoColoursLookTheSameToAnyoneAtAll",
                "whiteIsTheLightestAndBlackIsTheDarkest",
                "blackIsGreyRatherThanPurple", "noneOfThemIsNeon",
                "everyOneOfThemStandsOffThePageItIsDrawnOn",
            )),
        Feature(Area.SHELL, "Toasts", "util.js", logic = true, done = true,
            tests = listOf("navigatingClearsAStaleToast", "questionMarkToasts", "theHelpKeyToasts")),

        // -------------------------------------------------------- library
        Feature(Area.LIBRARY, "Card grid, 100 per page, with paging", "search.js", logic = true, done = true,
            tests = listOf(
                "pagesAreRoundedUp", "theRangeShownReadsTheWayAPersonWouldSayIt", "nextAndPreviousStopAtTheEnds",
                "paginationIsPageTimesSize", "showsTheRangeAndTotal", "previousIsDeadOnTheFirstPageAndNextOnTheLast",
                "thePagerMovesAndTheRangeFollows", "theLibraryShowsTheRangeAndTheRows",
            )),
        // Taken out on request: the box and the reference that
        // explained it. `parseQueryBox` stays in the core — it is
        // tested and costs nothing — but nothing types into it.
        Feature(Area.LIBRARY, "Filter panel folds into ten groups, each on its own", "search.js GROUPS", logic = true, done = true,
            tests = listOf(
                "everythingIsFoldedAwayToStart", "eachGroupFoldsOnItsOwn", "aClosedGroupDoesNotRenderItsControls",
                "aGroupWithSomethingSetSaysHowMany", "aGroupWithNothingSetHasNoBadge",
                "everyFieldIsCountedByExactlyOneGroup", "everyFilterGroupOpensAndClosesOnItsOwn",
                "theSortIsOnTheLeftAndExportOnTheRight", "theUnlockDialogHasPaddingRoundItsWords",
                "theFilterGroupsAreAlwaysOnThePage", "andTheyAllStartFoldedAway",
                "aGroupHoldingAFilterOpensItselfSoTheFilterCanBeSeen",
                "thePanelHasEveryGroupTheOldPageHad",
            )),
        Feature(Area.LIBRARY, "Filter panel: owner, pool, deck, finish, quantity", "filters.js", logic = true, done = true,
            tests = listOf(
                "bothMeansNoOwnerClause", "poolFiltersOnFreeCopies", "deckAnyAndNoneAreTheirOwnClauses",
                "theThreePoolsAreTheThreeAnswers", "deckByNameIsBoundBySlug",
                "finishIsAnEqualsAndAnEmptyFinishMeansAny", "theCollectionGroupWritesItsFields",
                "andWhoseCollectionItIsLivesWithTheOtherFilters",
            )),
        Feature(Area.LIBRARY, "Search boxes read like Scryfall: words, \"phrases\", !exclusions",
            "filters.js", logic = true, done = true,
            tests = listOf(
                "wordsAreSeparateTerms", "quotesHoldAPhraseTogether", "aPhraseAndLooseWordsMix",
                "bangExcludes", "andItExcludesAPhraseToo", "aBangInsideAWordIsJustACharacter",
                "aLoneBangIsNotATerm", "anUnclosedQuoteTakesTheRest", "nothingTypedIsNoTerms",
                "twoWordsInTheNameBoxAreTwoConditions", "aQuotedNameIsOneCondition",
                "anExcludedWordBecomesANotAndSurvivesANullColumn",
                "theOracleBoxAndsItsWordsInsteadOfQuotingTheLot",
                "aQuotedOracleSearchIsStillAPhrase", "punctuationInTheOracleBoxIsNotFtsSyntax",
                "anExcludedOracleWordIsSubtractedRatherThanMatched", "onlyExclusionsStillWorks",
                "severalExclusionsAreOredBeforeBeingSubtracted", "everyBoxIsCaseInsensitive",
                "aRulesTextSearchLooksAtTheRulesTextAndNothingElse",
                "andAnExclusionIsScopedTheSameWay",
                "theWildcardsOfLikeAreStillCharacters",
            )),
        Feature(Area.LIBRARY, "Filter panel: name, oracle text, flavour, artist, watermark, type line", "filters.js", logic = true, done = true,
            tests = listOf(
                "aNameSearchLooksAtBothFacesAndIsBound", "oracleTextUsesFullTextSearch",
                "thereIsNoSeparateLiteralTextBoxAnyMore", "everyFieldOnThePanelChangesTheQuery",
                "theWordFieldsWriteTheirOwnColumns", "theNameBoxAcceptsTypedCharacters",
                "andTheTypedNameReachesTheFilters", "clearingTheBoxClearsTheFilter",
            )),
        Feature(Area.LIBRARY, "Colour filter with exactly / at most / at least / any of", "filters.js", logic = true, done = true,
            tests = listOf(
                "exactlyBindsColoursInAlphabeticalOrder", "atMostExcludesEveryColourNotChosen",
                "atLeastRequiresEveryColourChosen", "anyOfIsOneOrClause", "anyOfIncludesColourlessWhenAsked",
                "theColourTargetPicksTheColumn", "allFourModesAreOfferedAndStick",
                "coloursAccumulateRatherThanReplacingEachOther",
            )),
        Feature(Area.LIBRARY, "Filter panel: cmc, power, toughness, rarity, set, keyword, tag, format", "filters.js", logic = true, done = true,
            tests = listOf(
                "powerComparesOnlyRealNumbers", "rarityBecomesAnInListWithOnePlaceholderEach",
                "setCodesAreComparedLowercase", "keywordsAreLowercasedAndEveryOneMustMatch",
                "tagsMatchTheSlugExactly", "aFormatWithoutAStatusStillAsksForLegal", "theRangesWriteBothEnds",
                "tokensAddOnEnterAndRemoveOnClick",
            )),
        Feature(Area.LIBRARY, "Boolean flags — reserved, game changer, full art and the rest", "filters.js", logic = true, done = true,
            tests = listOf(
                "flagsGoThreeWays", "theGameChangerFlagUsesItsDatabaseColumn", "theFlagsAreThreeValued",
                "onlySetFlagsAppearInTheUrl", "everyFieldOnThePanelChangesTheQuery",
            )),
        Feature(Area.LIBRARY, "Sorting, price descending by default", "filters.js SORTS", logic = true, done = true,
            tests = listOf(
                "theDefaultsAreBothCollectionsAndPriceDescending", "nullsSortLastWhicheverDirection",
                "nameIsAlwaysTheTiebreak", "sortingByTheSameColumnFlipsDirection", "sortingReturnsToPageOne",
                "theSortIsADropdownOfEverySortWithTheCurrentOneChosen", "theSortDropdownOffersEverySortAndPicksOne",
                "theDirectionButtonFlipsAndSaysWhichWayItIs",
            )),
        Feature(Area.LIBRARY, "Filter state in the URL, so a search is a link", "filters.js toHash/fromHash", logic = true, done = true,
            tests = listOf(
                "theDefaultSearchIsABareHash", "anEmptyQueryStringIsTheDefaults", "onlyWhatDiffersIsWritten",
                "everyFieldSurvivesTheRoundTrip", "spacesAndPunctuationSurvive", "unicodeSurvives",
                "anUnknownParameterIsIgnoredRatherThanCrashing", "aNonsensePageFallsBackToOne",
            )),
        Feature(Area.LIBRARY, "Name autocomplete against all of Scryfall", "complete.js", logic = true, done = true,
            tests = listOf(
                "oneCharacterIsNotWorthAsking", "theHighlightWrapsBothWays",
                "pickingPutsTheNameInTheBoxAndClosesTheList", "itNeverShowsMoreThanTen",
                "namesComeBackAndTheTermIsSentAsTyped", "aFailureIsNoSuggestionsRatherThanAnException",
                "autocompleteOffersWhatCameBack", "andItAlsoAsksScryfallForSuggestions",
            )),
        Feature(Area.LIBRARY, "Export the whole result as a decklist or to the clipboard", "search.js", logic = true, done = true,
            tests = listOf(
                "theExportQueryIsUnpagedAndCapped", "theExportKeepsTheSearchItWasMadeFrom",
                "aDecklistIsQuantityAndName", "aTwoFacedCardExportsWithBothNames",
                "anEmptySearchExportsNothingRatherThanABlankLine", "theFilenameCarriesTheDate", "bothWaysOutOfALibraryAreOffered",
                "exportAsksWhereItIsGoing",
                "theExportTextIsWhatGoesOnTheClipboard",
            )),
        Feature(Area.LIBRARY, "Prices fetched and shown, with a reason when missing", "prices.js", logic = true, done = true,
            tests = listOf(
                "centsMatterUnderTenAndDoNotAboveIt", "thousandsAreGrouped", "noPriceIsADashNotAZero",
                "anUnreleasedPrintingSaysWhenItArrives", "aTokenIsNotSoldSingly", "aPriceBeatsAReason",
                "theBadgesSayWhatIsSpareAndWhatItIsWorth", "aCardWithNoSpareCopySaysWhereTheyWent",
            )),

        // ---------------------------------------------------------- decks
        Feature(Area.DECKS, "Deck tiles: name, colour pips, commander, bracket, art banner", "decks.js", logic = true, done = true,
            tests = listOf(
                "readingItACharacterAtATimeWouldBeNonsense", "theTileNameStopsAtTheEmDash",
                "theBannerPrefersACardWeOwn", "andFallsBackToAskingScryfallByName",
                "theCommanderNameDropsItsSetAnnotation", "aTileShowsTheCommanderWithoutItsSetAnnotation",
                "theDeckTileWearsItsCommandersArt", "theDeckTileShowsTheTileWidthNameAndItsIdentity",
            )),
        Feature(Area.DECKS, "Deck detail with its card list", "decks.js", logic = true, done = true,
            tests = listOf(
                "gapsAreTheCardsTheOwnerIsShortOf", "oneDecksCardsAreBoundBySlug", "closingADeckForgetsItsCards",
                "decksGroupByOwnerInAStableOrder", "aDeckDetailCountsCardsAndFlagsWhatIsMissing",
                "openingADeckAsksThroughTheCallback", "decksAreGroupedByOwner", "eachRouteSaysWhatItNeeds",
            )),
        Feature(Area.CARD, "A card is its own page, at its own address",
            "app.js", logic = true, done = true,
            tests = listOf(
                "aCardGoesIntoTheAddressAndComesBackOut", "aColonInTheNameIsEncodedNotLeftInThePath",
                "aSlashInTheNameSurvivesThePath", "anAccentSurvivesTheTrip",
                "rubbishIsNoCardRatherThanAWrongOne", "aCardIsItsOwnAddressAndNothingElses",
                "aLinkToACardDoesNotCarryTheDeckItWasOpenedFrom",
                "aLinkToACardDoesNotCarryTheSearchEither",
                "theAddressSaysWhichCardTheStateIsShowing",
                "aCardHeldWhileSomewhereElseIsNotTheCardOnScreen",
                "openingACardIsAStepBackCanUndo", "anotherCardIsAnotherStep",
                "backGoesToThePageTheCardWasOpenedFrom",
                "aCardOpenedFromALinkGoesBackToTheLibrary",
                "theDeckIsStillThereWhenYouComeBackToIt",
                "aCardIsNotSomewhereTheMenuOffers",
                "aCardOpensFromTheGridAndTheAddressIsTheCardAlone",
                "aCardOpenedFromALinkLoadsItself", "backLeavesTheCardWithoutLeavingTheSite",
                "theBackButtonGoesWhereTheBrowsersDoes",
                "aCardOpenedFromALinkStillHasSomewhereToGoBackTo",
                "openingAndLeavingThreeTimesStillWorks", "aCardFromADeckGoesBackToThatDeck",
                "aCardOpensAtTheTopOfItself",
                "backLeavesTheCardForThePageItWasOpenedFrom", "theCardPageOffersAShare",
                "theLinkToACardIsTheCardAndNothingElse",
            )),
        Feature(Area.LIBRARY, "A search survives being sent to somebody and reopened",
            "app.js", logic = true, done = true,
            tests = listOf(
                "openingADeckIsAStepBackComesBackFrom", "andForwardGoesBackIntoIt",
                "aDeckOpenedFromALinkShowsItsCards", "changingAFilterIsNotAStepBackHasToUndo",
                "aRestoredSearchFillsTheBoxThatShowsIt",
                "andItDoesNotArriveWithASuggestionListHangingOpen",
                "anEmptySearchClearsTheBoxRatherThanLeavingTheLastWordInIt",
                "aRestoredSearchKeepsThePageItWasSentOn",
                "everyFieldThePanelShowsSurvivesTheRoundTrip",
            )),
        Feature(Area.SHELL, "A screen says it is loading, and says why it is empty",
            "app.js", logic = true, done = true,
            tests = listOf(
                "aScreenBeingFetchedSaysSoRatherThanSayingItIsEmpty",
                "andAFailedFetchSaysWhyRatherThanSayingItIsEmpty",
                "everyScreenThatFetchesCanSayBothThings",
                "aScreenWithNothingToFetchIsLeftAlone",
            )),
        Feature(Area.DECKS, "Tokens a deck makes, as real cards below the list",
            "decks.js", logic = true, done = true,
            tests = listOf(
                "aTokenComesBackAsACardWithArt", "twoBirdsThatDifferOnlyByColourAreTwoTokens",
                "howManyCardsMakeEachOne", "theSameTokenInSeveralSetsCollapsesToOne",
                "onlyTokenPartsCount", "aDeckThatMakesNothingAsksScryfallNothingTwice",
                "noIdsMeansNoRequestAtAll", "aFailureLosesTheRowRatherThanTheDeck",
                "theTokensAreCardsBelowTheList",
                "twoTokensThatDifferOnlyByColourDoNotLookIdentical",
                "aColourlessTokenSaysSoRatherThanShowingNothing",
                "aDeckWithNoTokensHasNoTokenPanel", "aTokenCarriesWhereToBuyOne",
                "andNullWhenScryfallHasNoListing", "aTokenYouCanBuyLinksToTcgplayer",
                "aTokenNobodySellsStaysARowRatherThanALinkToNowhere",
            )),
        Feature(Area.DECKS, "Deck analysis: curve, colour, types, rarity, tokens",
            "decks.js", logic = true, done = true,
            tests = listOf(
                "aCostIsReadSymbolBySymbol", "onlyColouredPipsCount", "aHybridCountsForBothHalves",
                "manaValueAddsUpTheGenericAndTheRest", "theCurveLeavesTheLandsOut",
                "everythingAboveSevenIsOneColumn", "aCardNobodyOwnsIsNotANoughtDrop",
                "theAverageAndMedianAreOverSpellsOnly", "pipsAreCountedPerCopy",
                "sourcesComeFromWhatEachCardCanProduce", "aColourAskedForWithNoSourceIsCalledOut",
                "aColourWithSourcesAndPipsIsNotCalledOut", "typesAndRaritiesAreCountedByCopies",
                "theLandShareIsAPercentageOfTheWholeDeck", "whatIsMissingAndWhatItIsWorth",
                "aBarKnowsHowWideToDraw",                                                                 "aDeckWithNoCardsAnalysesToNothingRatherThanCrashing",
                "aTokenIsARealCardWithRealArt", "aDeckCarriesTheIdsItsTokensAreLookedUpBy",
                "openingADeckClearsTheTokensOfTheLastOne",
                "eachColourSplitIsAlsoDrawnAsARing", "aRingWithNothingInItIsNotDrawn",
                "everySectionIsDrawn", "theTallestColumnFillsTheChartAndTheRestAreToScale",
                "eachColourShowsWhatItNeedsAgainstWhatItMakes",
                "aColourWithNoSourceIsSaidOutLoud", "theCardsNobodyOwnsAreNamedRatherThanFoldedIn",
                "nothingRunsOffTheEdgeAtPhoneWidth",
                "theColoursAreNotWashedOut",
                "anOpenedDeckFitsOnAPhoneHoweverLongItsCommanderIsCalled",
            )),
        Feature(Area.DECKS, "Deck detail: commander banner, grouped by type, tap a card to open it",
            "decks.js", logic = true, done = true,
            tests = listOf(
                "aCardGoesUnderTheMostSpecificTypeItHas",
                "onlyTheFrontFaceDecidesWhichSectionItIsIn",
                "aCardWithNoTypeLineAtAllSaysSoRatherThanHiding",
                "theCommanderIsItsOwnSectionWhateverItIsMadeOf",
                "theSectionsComeOutInReadingOrderAndAlphabeticalInside",
                "anEmptySectionIsNotShownAtAll",
                "theCommanderIsAlsoOfferedOnItsOwnForTheBanner",
                "aCardCarriesEnoughToOpenItsDrawerAndDrawItsThumbnail",
                "theCommanderGetsABannerAcrossTheTop", "aDeckWithNoCommanderGetsNoEmptyBand",
                "theBannerTextSitsOverAWashSoItCanBeRead", "theListIsGroupedByTypeInReadingOrder",
                "eachGroupIsAlphabeticalInside", "everyCardHasASquareThumbnail",
                "tappingACardAsksForItsDetail", "aCardRowSaysItIsSomethingYouCanPress",
                "aCardNobodyOwnsStillListsWithoutABrokenPicture",
            )),
        Feature(Area.DECKS, "Edit a deck's list, commander as its own field", "decks.js", logic = true, done = true,
            tests = listOf(
                "theCommanderComesOutOfTheList", "aDeckWithNoCommanderRowFallsBackToTheStoredName",
                "savingIsNotOfferedUntilTheServerHasSaidWhatWouldHappen", "editingAfterAReviewTakesSaveAwayAgain",
                "changingOnlyTheCommanderAlsoCountsAsAnEdit", "thePositionalArraysBecomeSomethingReadable",
                "theEditDialogWillNotSaveBeforeItHasReviewed", "theEditDialogOffersSaveOnceThePlanIsIn",
            )),
        Feature(Area.DECKS, "Disassemble a deck back into bulk", "decks.js", logic = true, done = true,
            tests = listOf(
                "itWillNotFireBeforeTheDryRunComesBack", "theWarningSaysAllOfIt", "oneCardIsNotOneCards",
                "doneMeansDone", "theDisassembleDryRunReads", "disassembleWillNotFireBeforeTheDryRunIsBack",
                "disassembleArmsOnceTheDryRunIsBack", "disassembleSaysWhatItWillDoAndWillNotFireEarly",
            )),
        Feature(Area.DECKS, "New deck wizard: format, owner, name, commander, cards, sourcing", "newdeck.js", logic = true, done = true,
            tests = listOf(
                "startsAtTheFormatWithNothingChosen", "theCommanderStepOnlyExistsForFormatsThatWantOne",
                "aCommanderFormatWillNotPassTheCommanderStepEmpty", "anUnnamedDeckGoesNoFurther",
                "everyCardNeedsASourceBeforeADeckCanBeCreated", "whatIsBeingBoughtIsListedSeparately",
                "jumpingAheadLandsOnTheLastStepActuallyAnswered",
                "theNewDeckWizardWillNotLeaveTheFirstStepUnanswered",
            )),
        Feature(Area.DECKS, "Card name validation against Scryfall in the wizard", "newdeck.js", logic = true, done = true,
            tests = listOf(
                "everyNameKnownIsOk", "aTypoComesBackWithItsSuggestion", "anUnknownNameWithNoNearMissIsStillReported",
                "namesMustBeCheckedBeforeSourcing", "aFailedCheckBlocksTheRestOfTheWizard",
                "editingTheListThrowsAwayTheCheckAndEverySourcingChoice",
            )),

        // ---------------------------------------------------------- stats
        Feature(Area.STATS, "Collection totals and breakdowns", "stats.js", logic = true, done = true,
            tests = listOf(
                "theDefaultScopeIsEveryone", "anUnscopedQueryStillSlotsIntoAWhere", "theSideBySideIsAlwaysBoth",
                "totalsDecode", "missingNumbersAreZeroRatherThanACrash", "anEmptyResultIsZeroesNotAnException",
                "statsShowTheTotals", "anUnpricedCollectionSaysSoRatherThanShowingZero",
            )),
        Feature(Area.STATS, "Per-owner scoping at #/stats/matt and /kayla", "stats.js", logic = true, done = true,
            tests = listOf(
                "statsScopeComesOutOfTheRoute", "aScopedQueryBindsTheOwnerOncePerSubquery",
                "theActiveScopeIsMarkedAndSwitchingAsksForTheOther", "aRouteParses",
            )),

        // ---------------------------------------------------------- query
        Feature(Area.QUERY, "Free SQL against the collection, read-only", "console.js", logic = true, done = true,
            tests = listOf(
                "aTableKeepsColumnOrderAndNulls", "nothingToRunIsNotRunnable",
                "aFailureClearsTheStaleResultRatherThanLeavingItOnScreen", "runIsRefusedWithNothingToRun",
                "aResultRendersAsARealTable", "anErrorReplacesTheStaleResultRatherThanSittingAboveIt",
                "anEmptyResultSaysSoRatherThanShowingAnEmptyTable",
                "theConsoleSendsWhatWasTypedAndLetsTheServerRefuseIt",
            )),

        // ---------------------------------------------------------- entry
        Feature(Area.ENTRY, "Four step wizard: which, list, who, review", "manage.js", logic = true, done = true,
            tests = listOf(
                "startsWithNothingChosen", "aDirectionIsNeededBeforeAnythingElse", "anEmptyListGoesNoFurther",
                "theStepperOnlyOffersStepsAlreadyAnswered", "enterMoreClearsEverything",
                "theWizardWalksToTheOwnerStep", "choosingADirectionEnablesContinue",
                "theStepperRefusesStepsNotYetAnswered",
            )),
        Feature(Area.ENTRY, "Mandatory dry run before any write", "manage.js", logic = true, done = true,
            tests = listOf(
                "applyIsUnreachableUntilTheServerHasSaidWhatItWouldDo", "aPreviewThatResolvedNothingOffersNoWrite",
                "applyIsNotOfferedTwice", "editingTheListThrowsAwayTheDryRunItWasTakenAgainst",
                "changingTheOwnerThrowsAwayTheDryRunToo", "noWriteIsOfferedBeforeADryRun",
                "applyIsNotOfferedWithoutADryRun", "aPreviewSaysDryRunAndCarriesTheToken",
            )),
        Feature(Area.ENTRY, "Owner never preselected", "manage.js", logic = true, done = true,
            tests = listOf(
                "neitherOwnerIsAssumed", "nothingIsPreselectedInTheWizard", "theWizardWalksToTheOwnerStep",
                "testNeitherOwnerIsPreselectedAndPreviewIsNotOfferedUntilOneIs",
                "reusePutsTheListBackWithoutTheOwner", "aShareOpensTheWizardWithTheListAlreadyInIt",
            )),
        Feature(Area.ENTRY, "Decklist and CSV parsing", "manage.js, parse.js", logic = true, done = true,
            tests = listOf(
                "aCsvNeedsAHeaderNamingTheCardColumn", "aCommaInACardNameIsNotACsv", "aCsvHeaderIsNotACard",
                "commentsAreNotCards", "sectionHeadersAreNotCards", "cardLinesSkipTheSameThingsCountingDoes",
                "csvCardLinesDropTheHeader", "aSharedListArrivesWithNothingElseDecided",
            )),
        Feature(Area.ENTRY, "File upload into the list box", "manage.js", logic = true, done = true,
            tests = listOf(
                "aFileNeverEatsWhatWasTyped", "twoMegabytesIsTheCeiling", "theSizeReadsLikeASize",
                "itSaysWhatCameOffDisk", "theFileDropIsOnTheListStep", "theFileButtonIsOnTheListStep",
            )),
        Feature(Area.ENTRY, "Recent history, with reuse", "manage.js", logic = true, done = true,
            tests = listOf(
                "newestFirst", "itStopsAtThirty", "onlyTwelveAreShown", "itSurvivesARoundTripThroughAStore",
                "rubbishInTheStoreIsNoHistoryRatherThanACrash", "aFinishedEntryBecomesARow",
                "anUnfinishedEntryIsNotRecorded", "recentEntriesCanBePutBackInTheBox",
            )),

        // ----------------------------------------------------------- card
        Feature(Area.CARD, "Card detail drawer with art, prices, legalities, rulings", "card.js", logic = true, done = true,
            tests = listOf(
                "artIsDerivedFromTheIdAlreadyOnTheRow", "aMissingOrShortIdGivesNoUrlRatherThanABrokenOne",
                "printingsDecodeAndSumToWhatIsOwned", "theQueriesBindTheNameAndNothingElse",
                "theCardSheetSaysWhatIsOwnedAndWhatIsFree", "theArtComesOffScryfallByTheIdOnTheRow",
                "theDrawerAsksForLegalitiesAndRulings", "aLegalityKnowsWhetherItIsOneAndReadsAsEnglish", "theDrawerDecodesWhatThoseQueriesReturn",
            )),
        Feature(Area.CARD, "Every printing shows its price and links to TCGplayer",
            "card.js", logic = true, done = true,
            tests = listOf(
                "aPrintingCarriesItsPriceAndWhereToBuyIt", "printingsAskTheFinishAwarePriceView",
                "aPrintingYouCanBuyLinksToTcgplayer",
                "aPrintingNobodySellsStaysARowRatherThanALinkToNowhere",
                "aPrintingQuotesThePriceForTheFinishItIsIn", "aPrintingLineStaysOnOneLine",
                "aPrintingLineSurvivesAPhone", "aPrintingSaysWhereTheLinkGoes",
            )),
        Feature(Area.CARD, "Which decks a card is in, and how many are free", "card.js", logic = true, done = true,
            tests = listOf(
                "proxiesDoNotCountAgainstWhatIsFree", "moreDecksThanCopiesIsFlaggedAndFreeNeverGoesNegative",
                "theQueriesBindTheNameAndNothingElse", "theCardSheetSaysWhatIsOwnedAndWhatIsFree", "aProxyDoesNotEatACopy",
                "aCardSaysWhoOwnsHowManyRatherThanBelongingToOnePerson",
                "somebodyWhoOwnsNoneButWantsOneStillGetsALine", "aProxyDoesNotEatAnyonesCopy",
                "aCardNobodyOwnsAndNobodyWantsHasNoOwners",
                "theOwnersAddUpToWhatTheWholeCollectionHas", "theCardQueriesAskAboutEverybody", "thePageSaysWhoOwnsHowMany",
                "aPrintingSaysWhoseCopyItIs", "aDeckRowSaysWhoseDeckItIs",
                "theOwnerLinesStayOnOneLineOnAPhone",
                "aCardWithNoSpareCopySaysWhereTheyWent",
            )),

        // ----------------------------------------------------------- logs
        Feature(Area.LOGS, "Request log with filtering", "logs.js", logic = true, done = true,
            tests = listOf(
                "failuresAreStatusOrLevel", "slowIsOverASecond", "theErrorsToggleNarrowsWithoutLosingTheRest",
                "logLinesDecodeFromNamedColumns", "theLogShowsItsLinesAndCountsTheFailures",
                "narrowingToErrorsDoesNotThrowTheRestAway",
            )),
        Feature(Area.LOGS, "Log summary counts", "logs.js", logic = true, done = true,
            tests = listOf(
                "failuresAreStatusOrLevel", "theErrorsToggleNarrowsWithoutLosingTheRest",
                "theLogShowsItsLinesAndCountsTheFailures",
            )),

        // ---------------------------------------------------------- admin
        Feature(Area.ADMIN, "Password unlock, token kept until locked", "admin.js", logic = true, done = true,
            tests = listOf(
                "anEmptyTokenIsNotUnlocked", "lockingForgetsTheToken", "unlockSendsThePasswordAndNoToken",
                "aTokenlessServerReplyIsARefusalNotASilentSuccess", "aWrongPasswordSurfacesTheServersOwnWords",
                "anExpiredTokenIsReportedNotSwallowed", "theUnlockDialogAsksAndHandsThePasswordBack",
                "lLocksAndUnlocks",
            )),
        Feature(Area.ADMIN, "Gated views unreachable and invisible while locked", "app.js, admin.js", logic = true, done = true,
            tests = listOf(
                "lockedHidesTheAdminViewsEntirely", "aGatedRouteBouncesWhileLocked",
                "bouncingKeepsTheQueryStringSoNothingTypedIsLost",
                "navigatingToAGatedViewWhileLockedLandsSomewhereUsable",
                "lockingWhileOnAGatedViewIsCaughtByLandingAgain", "gatedShortcutsAreAsHiddenAsTheirTabs",
                "gatedTabsAreAbsentWhileLocked", "theAdminActionsAreHiddenWhileLocked",
            )),

        // ---------------------------------------------------------- share
        Feature(Area.SHARE, "Receive a shared file from another Android app", "SharedFile.kt", logic = true, done = true,
            tests = listOf(
                "testReadsACsvSharedAsAContentUri", "testReadsAPlainDecklist", "testReadsSeveralFilesAtOnce",
                "testOpenWithIsReadTheSameWay", "testSharedTextIsTakenToo", "testALargeExportSurvivesIntact",
                "testTheManifestClaimsAFileShare", "testASharedCsvArrivesOnScreen",
            )),
        Feature(Area.SHARE, "Read it whatever its declared MIME type", "SharedFile.kt", logic = true, done = true,
            tests = listOf(
                "testReadsAFileWhateverItsDeclaredTypeIs", "testOpenWithIsReadTheSameWay",
                "testTheManifestClaimsAFileShare",
            )),
        Feature(Area.SHARE, "Say what arrived when nothing usable did", "SharedFile.kt", logic = true, done = true,
            tests = listOf(
                "testABareLinkIsNotACardList", "testBinaryIsRefusedAndSaysSo",
                "testAnEmptyShareIsReportedRatherThanIgnored", "testABinaryShareSaysWhatWasWrongInsteadOfGoingQuiet",
                "textIsTextAndBinaryIsNot", "aFewOddCharactersAreStillText",
            )),
    )

    val done: List<Feature> get() = features.filter { it.done }
    val logicOnly: List<Feature> get() = features.filter { it.logic && !it.done }
    val remaining: List<Feature> get() = features.filterNot { it.done }
    val untouched: List<Feature> get() = features.filterNot { it.logic || it.done }

    val percentDone: Int
        get() = if (features.isEmpty()) 100 else done.size * 100 / features.size

    /** What is left, grouped, for a build to print rather than a person to guess. */
    fun report(): String = buildString {
        appendLine(
            "Ported ${done.size} of ${features.size} features (${percentDone}%). " +
                "${logicOnly.size} more have their rules shared but no screen yet.",
        )
        Area.entries.forEach { area ->
            val inArea = features.filter { it.area == area }
            if (inArea.isEmpty()) return@forEach
            val left = inArea.count { !it.done }
            appendLine("  ${area.label}: ${inArea.size - left}/${inArea.size}")
            inArea.filterNot { it.done }.forEach {
                val state = if (it.logic) "rules only" else "todo     "
                appendLine("      $state  ${it.what}  [${it.source}]")
            }
        }
    }
}
