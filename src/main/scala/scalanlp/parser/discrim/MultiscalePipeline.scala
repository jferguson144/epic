package scalanlp.parser.discrim

import scalanlp.util._
import scalanlp.parser.projections.GrammarProjections
import scalanlp.parser._
import java.io._
import projections.GrammarProjections._
import scalala.tensor.dense.DenseVector
import scalanlp.trees.UnaryChainRemover.ChainReplacer
import scalala.library.Library
import scalanlp.parser.ParseChart._
import scalala.library.Library._
import scalala.tensor.{Counter, Counter2}
import scalala.tensor.::
import logging._
import scalanlp.optimize.{BatchDiffFunction, FirstOrderMinimizer, CachedBatchDiffFunction}

/**
 * Runs something not unlike Petrov's 2008 EMNLP paper.
 *
 * @author dlwh
 */
object MultiscalePipeline extends ParserPipeline {

  type MyFeaturizer = Featurizer[(String,Seq[Int]),String]
  type MyObjective = LatentDiscrimObjective[String,(String,Seq[Int]),String]

  case class SpecificParams(lastParser: File = null)
  implicit def specificManifest = manifest[SpecificParams]

  type Params = LatentParams[SpecificParams];
  protected lazy val paramManifest = { manifest[Params]}

  def mkObjective(params: MultiscalePipeline.Params,
                  latentFeaturizer: MultiscalePipeline.MyFeaturizer,
                  trainTrees: IndexedSeq[TreeInstance[String, String]],
                  indexedProjections: GrammarProjections[String, (String, Seq[Int])],
                  xbarParser: ChartBuilder[ParseChart.LogProbabilityParseChart, String, String],
                  openTags: Set[(String, Seq[Int])],
                  closedWords: Set[String]) = {
    val r = new LatentDiscrimObjective(latentFeaturizer,
      trainTrees,
      indexedProjections,
      xbarParser,
      openTags,
      closedWords) with ConfiguredLogging;

    r
  }


  def getFeaturizer(params: MultiscalePipeline.Params,
                    initLexicon: Counter2[String, String, Double],
                    initBinaries: Counter2[String, BinaryRule[String], Double],
                    initUnaries: Counter2[String, UnaryRule[String], Double],
                    numStates: Int) = {
    val factory = params.featurizerFactory;
    val featurizer = factory.getFeaturizer(initLexicon, initBinaries, initUnaries);
    val latentFeaturizer = new MultiscaleFeaturizer(featurizer)
    val weightsPath = params.oldWeights;
    println("old weights: " + weightsPath);
    if(weightsPath == null) {
      latentFeaturizer
    } else {
      val weights = readObject[(DenseVector[Double],Counter[Feature[(String,Seq[Int]),String],Double])](weightsPath)._2;
      println(weights.size,weights.valuesIterator.count(_ == 0.0),weights.valuesIterator.count(_.abs < 1E-4))

      new CachedWeightsFeaturizer(latentFeaturizer, weights, randomize= false, randomizeZeros = true);
    }
  }

  def determineInitialRules(xbarGrammar: Grammar[String],  params: Params) = {
    if(params.specific.lastParser == null) {
      val result = Counter[Rule[(String,Seq[Int])],Double]()
      for( p@(_,rule) <- xbarGrammar.binaryRules.nonzero.keys) {
        val splitRule = BinaryRule(rule.parent -> Seq.empty, rule.left -> Seq.empty, rule.right -> Seq.empty)
        result(splitRule) = xbarGrammar.binaryRules(p)
      }

      for( p@(_,rule) <- xbarGrammar.unaryRules.nonzero.keys) {
        val splitRule = UnaryRule(rule.parent -> Seq.empty, rule.child -> Seq.empty)
        result(splitRule) = xbarGrammar.unaryRules(p)
      }

      result

    } else {
      val oldParser = readObject[SimpleChartParser[String,(String,Seq[Int]),String]](params.specific.lastParser)
      val result = Counter[Rule[(String,Seq[Int])],Double]()
      for( p@(_,rule) <- oldParser.builder.grammar.binaryRules.keysIterator) {
        result(rule) = oldParser.builder.grammar.binaryRules(p)
      }

      for( p@(_,rule) <- oldParser.builder.grammar.unaryRules.nonzero.keys) {
        result(rule) = oldParser.builder.grammar.unaryRules(p)
      }

      result
    }

  }

  def splitRules(rules: Counter[Rule[(String,Seq[Int])],Double], splitAllRules: Boolean = false) = {
    val splitter = new ConditionalRuleSplitter(fracToSplit = if(splitAllRules) 1.0 else 0.5, minRuleValue = if(splitAllRules) -1 else 1E-4)
    def split(sym: (String,Seq[Int])) = if(sym._1 == "") Seq(sym._1 -> Seq(0)) else Seq((sym._1,0 +: sym._2), (sym._1,1 +: sym._2))
    def proj(sym: (String,Seq[Int])) = sym._1
    splitter.chooseRulesToSplit(rules, split, proj)
  }

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String,String]],
                  validate: Parser[String,String]=>ParseEval.Statistics,
                  params: Params) = {

    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees);
    import params._;

    val xbarParser = parser.optParser.getOrElse {
      val grammar: Grammar[String] = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    val initialRules = determineInitialRules(xbarParser.grammar, params)

    val (splitSymbols,theSplitRules) = splitRules(initialRules, splitAllRules = params.specific.lastParser == null)
    val allSplitSymbols = splitSymbols.mapValues(_.toIndexedSeq)
    println(allSplitSymbols)

    def split(s: String) = allSplitSymbols(s)
    def unsplit(s: (String,Any)) = s._1

    val indexedProjections = GrammarProjections(xbarParser.grammar, split _ , unsplit _ );

    val latentFeaturizer: MyFeaturizer = getFeaturizer(params, initLexicon, initBinaries, initUnaries, numStates)

    val openTags = Set.empty ++ {
      for(t <- initLexicon.nonzero.keys.map(_._1) if initLexicon(t,::).size > 50; t2 <- split(t).iterator ) yield t2;
    }

    val closedWords = Set.empty ++ {
      val wordCounts = sum(initLexicon)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 5).map(_._1);
    }

    val obj = mkObjective(params, latentFeaturizer, trainTrees, indexedProjections, xbarParser, openTags, closedWords)

    val init = obj.initialWeightVector + 0.0;

    type OptState = FirstOrderMinimizer[DenseVector[Double],BatchDiffFunction[DenseVector[Double]]]#State
    def evalAndCache(pair: (OptState,Int) ) {
      val (state,iter) = pair;
      val weights = state.x;
      if(iter % iterPerValidate == 0) {
        cacheWeights(params, obj,weights, iter);
        println("Validating...");
        val parser = obj.extractParser(weights);
        println(validate(parser))
      }
    }


    val cachedObj = new CachedBatchDiffFunction[DenseVector[Double]](obj);

    for( (state,iter) <- params.opt.iterations(cachedObj,init).take(maxIterations).zipWithIndex.tee(evalAndCache _);
         if iter != 0 && iter % iterationsPerEval == 0) yield try {
      val parser = obj.extractParser(state.x)
      ("MultiScale-" + iter.toString,parser)
    } catch {
      case e => println(e);e.printStackTrace(); throw e;
    }
  }


  def cacheWeights(params: Params, obj: MyObjective, weights: DenseVector[Double], iter: Int) = {
    println("Zeros:" + weights.size,weights.valuesIterator.count(_ == 0), weights.valuesIterator.count(_.abs < 1E-4))
    writeObject( new File("weights-"+iter +".ser"), weights -> obj.indexedFeatures.decode(weights))
  }
}