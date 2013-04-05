package hex.rf;

import hex.rf.DRF.DRFFuture;
import hex.rf.Tree.StatType;
import hex.rng.H2ORandomRNG.RNGKind;

import java.io.File;
import java.util.*;

import water.*;
import water.Timer;
import water.util.Utils;

/**
 * A RandomForest can be used for growing or validation. The former starts with a known target number of trees,
 * the latter is incrementally populated with trees as they are built.
 * Validation and error reporting is not supported when growing a forest.
 */
public class RandomForest {

  /* Seed initializer generated by:  cat /dev/urandom| tr -dc '0-9a-f' | fold -w 16| head -n 1' */
  private static final long ROOT_SEED_ADD  = 0x026244fd935c5111L;
  private static final long TREE_SEED_INIT = 0x1321e74a0192470cL;

  /** Build random forest for data stored on this node. */
  public static void build(
                      final DRF drf,
                      final Data data,
                      int ntrees,
                      int maxTreeDepth,
                      double minErrorRate,
                      StatType stat,
                      boolean parallelTrees,
                      int numSplitFeatures) {
    Timer  t_alltrees = new Timer();
    Tree[] trees      = new Tree[ntrees];
    Utils.pln("[RF] number of split features: "+ drf.numSplitFeatures());
    Utils.pln("[RF] starting RF computation with "+ data.rows()+" rows ");

    Random rnd = Utils.getRNG(data.seed() + ROOT_SEED_ADD);
    for (int i = 0; i < ntrees; ++i) {
      long treeSeed = rnd.nextLong() + TREE_SEED_INIT; // make sure that enough bits is initialized
      trees[i] = new Tree( data, maxTreeDepth, minErrorRate, stat, numSplitFeatures, treeSeed,
                           drf._job,i,drf._ntrees, drf._sample, drf._numrows,
                           drf._useStratifySampling, drf._strata,
                           drf._verbose, drf._exclusiveSplitLimit );
      if (!parallelTrees)   DRemoteTask.invokeAll(new Tree[]{trees[i]});
    }
    if(parallelTrees)DRemoteTask.invokeAll(trees);
    Utils.pln("All trees ("+ntrees+") done in "+ t_alltrees);
  }


  public static class OptArgs extends Arguments.Opt {
    String  file          = "smalldata/poker/poker-hand-testing.data";
    String  rawKey;
    String  parsedKey;
    String  validationFile;
    String  h2oArgs;
    int     ntrees        = 10;
    int     depth         = Integer.MAX_VALUE;
    int     sample        = 67;
    int     binLimit      = 1024;
    int     classcol      = -1;
    int     features      = -1;
    int     parallel      = 1;
    boolean outOfBagError = true;
    boolean stratify      = false;
    String  strata;
    String  weights;
    String  statType      = "entropy";
    long    seed          = 0xae44a87f9edf1cbL;
    String  ignores;
    int     cloudFormationTimeout = 10; // wait for up to 10seconds
    int     verbose       = 0; // levels of verbosity
    int     exclusive     = 0; // exclusive split limit, 0 = exclusive split is disabled
    String  rng           = RNGKind.DETERMINISTIC.name();
  }

  static final OptArgs ARGS = new OptArgs();

  public static Map<Integer,Integer> parseStrata(String s){
    if(s.isEmpty())return null;
    String [] strs = s.split(",");
    Map<Integer,Integer> res = new HashMap<Integer, Integer>();
    for(String x:strs){
      String [] arr = x.split(":");
      res.put(Integer.parseInt(arr[0].trim()), Integer.parseInt(arr[1].trim()));
    }
    return res;
  }

  public static void main(String[] args) throws Exception {
    Arguments arguments = new Arguments(args);
    arguments.extract(ARGS);
    String[] h2oArgs;
    if(ARGS.h2oArgs == null) { // By default run using local IP, C.f. JUnitRunner
      File flat = Utils.tempFile("127.0.0.1:54327");
      h2oArgs = new String[] { "-ip=127.0.0.1", "-flatfile=" + flat.getAbsolutePath() };
    } else {
      if(ARGS.h2oArgs.startsWith("\"") && ARGS.h2oArgs.endsWith("\""))
        ARGS.h2oArgs = ARGS.h2oArgs.substring(1, ARGS.h2oArgs.length()-1);
      ARGS.h2oArgs = ARGS.h2oArgs.trim();
      h2oArgs = ARGS.h2oArgs.split("[ \t]+");
    }
    H2O.main(h2oArgs);
    ValueArray va;
    // get the input data
    if(ARGS.parsedKey != null) // data already parsed
      va = DKV.get(Key.make(ARGS.parsedKey)).get();
    else if(ARGS.rawKey != null) // data loaded in K/V, not parsed yet
      va = TestUtil.parse_test_key(Key.make(ARGS.rawKey),Key.make(TestUtil.getHexKeyFromRawKey(ARGS.rawKey)));
    else { // data outside of H2O, load and parse
      File f = new File(ARGS.file);
      System.out.println("[RF] Loading file " + f);
      Key fk = TestUtil.load_test_file(f);
      va = TestUtil.parse_test_key(fk,Key.make(TestUtil.getHexKeyFromFile(f)));
      DKV.remove(fk);
    }
    if(ARGS.ntrees == 0) {
      Utils.pln("Nothing to do as ntrees == 0");
      UDPRebooted.T.shutdown.broadcast();
      return;
    }
    StatType st = ARGS.statType.equals("gini") ? StatType.GINI : StatType.ENTROPY;

    Map<Integer,Integer> strata = (ARGS.stratify && ARGS.strata != null) ? parseStrata(ARGS.strata) : null;

    double[] classWeights = null;
    if(ARGS.stratify && ARGS.strata != null) {
      Map<Integer,Integer> weights = parseStrata(ARGS.weights);
      int[] ks = new int[weights.size()];
      int i=0; for (Object clss : weights.keySet().toArray()) ks[i++]= (Integer)clss;
      Arrays.sort(ks);
      classWeights = new double[ks.length];
      i=0; for(int k : ks) classWeights[i++] = weights.get(k);
    }

    // Setup desired random generator.
    Utils.setUsedRNGKind(RNGKind.value(ARGS.rng));

    final int num_cols = va._cols.length;
    final int classcol = ARGS.classcol == -1 ? num_cols-1: ARGS.classcol; // Defaults to last column

    // Build the set of positive included columns
    BitSet bs = new BitSet();
    bs.set(0,va._cols.length);
    bs.clear(classcol);
    if (ARGS.ignores!=null)
      for( String s : ARGS.ignores.split(",") )
        bs.clear(Integer.parseInt(s));
    int cols[] = new int[bs.cardinality()+1];
    int idx=0;
    for( int i=bs.nextSetBit(0); i >= 0; i=bs.nextSetBit(i+1))
      cols[idx++] = i;
    cols[idx++] = classcol;     // Class column last
    assert idx==cols.length;
    assert ARGS.sample >0 && ARGS.sample<=100;
    assert ARGS.ntrees >=0;
    assert ARGS.binLimit > 0 && ARGS.binLimit <= Short.MAX_VALUE;

    Utils.pln("[RF] Arguments used:\n"+ARGS.toString());
    final Key modelKey = Key.make("model");
    DRFFuture drfResult = DRF.execute(modelKey,
                          cols,
                          va,
                          ARGS.ntrees,
                          ARGS.depth,
                          (ARGS.sample/100.0f),
                          (short)ARGS.binLimit,
                          st,
                          ARGS.seed,
                          ARGS.parallel==1,
                          classWeights,
                          ARGS.features, // number of split features or -1 (default)
                          ARGS.stratify,
                          strata,
                          ARGS.verbose,
                          ARGS.exclusive);
    DRF drf = drfResult.get();  // block on all nodes!
    RFModel model = UKV.get(modelKey);
    Utils.pln("[RF] Random forest finished in "+ drf._t_main);

    Timer t_valid = new Timer();
    // Get training key.
    Key valKey = drf.aryKey();
    if(ARGS.outOfBagError && !ARGS.stratify){
      Utils.pln("[RF] Computing out of bag error");
      Confusion.make( model, valKey, classcol, null, true).report();
    }
    // Run validation.
    if(ARGS.validationFile != null && !ARGS.validationFile.isEmpty()){ // validate on the supplied file
      File f = new File(ARGS.validationFile);
      System.out.println("[RF] Loading validation file " + f);
      Key fk = TestUtil.load_test_file(f);
      ValueArray v = TestUtil.parse_test_key(fk,Key.make(TestUtil.getHexKeyFromFile(f)));
      valKey = v._key;
      DKV.remove(fk);
      Confusion.make( model, valKey, classcol, null, false).report();
    }

    Utils.pln("[RF] Validation done in: " + t_valid);
    UDPRebooted.T.shutdown.broadcast();
  }
}
