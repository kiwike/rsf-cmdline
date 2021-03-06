package org.briljantframework.cmd;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.cli.*;
import org.apache.commons.math3.util.Pair;
import org.briljantframework.data.series.Series;
import org.briljantframework.mimir.classification.ClassifierEvaluator;
import org.briljantframework.mimir.classification.ClassifierValidator;
import org.briljantframework.mimir.classification.EnsembleEvaluator;
import org.briljantframework.mimir.classification.ProbabilityEstimator;
import org.briljantframework.mimir.classification.tree.ClassSet;
import org.briljantframework.mimir.classification.tree.TreeBranch;
import org.briljantframework.mimir.classification.tree.TreeLeaf;
import org.briljantframework.mimir.classification.tree.TreeNode;
import org.briljantframework.mimir.classification.tree.pattern.PatternDistance;
import org.briljantframework.mimir.classification.tree.pattern.PatternFactory;
import org.briljantframework.mimir.classification.tree.pattern.PatternTree;
import org.briljantframework.mimir.classification.tree.pattern.RandomPatternForest;
import org.briljantframework.mimir.data.*;
import org.briljantframework.mimir.data.timeseries.MultivariateTimeSeries;
import org.briljantframework.mimir.data.timeseries.SaxOptions;
import org.briljantframework.mimir.data.timeseries.TimeSeries;
import org.briljantframework.mimir.distance.SlidingSAXDistance;
import org.briljantframework.mimir.evaluation.Result;
import org.briljantframework.mimir.evaluation.partition.Partition;
import org.briljantframework.mimir.evaluation.partition.Partitioner;
import org.briljantframework.mimir.shapelet.IndexSortedNormalizedShapelet;
import org.briljantframework.mimir.shapelet.MultivariateShapelet;
import org.briljantframework.mimir.shapelet.NormalizedShapelet;
import org.briljantframework.mimir.shapelet.Shapelet;
import org.briljantframework.util.sort.ElementSwapper;

/**
 * A simple command line utility for running the random shapelet forest.
 */
public class Main {

  private static long getTotalThreadCPUTime() {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    threadMXBean.setThreadCpuTimeEnabled(true);
    long[] ids = threadMXBean.getAllThreadIds();
    long sum = 0;
    for (long id : ids) {
      long contrib = threadMXBean.getThreadCpuTime(id);
      if (contrib != -1)
        sum += contrib;
    }
    return sum;
  }

  public static void main(String[] args) {

    //args = new String[] {"-p", "-n", "100", "-a", "24", "-t", "30", "-l", "0", "-u", "1", "-r", "10",
    //        "dataset/synthetic_control/synthetic_control_TRAIN",
    //        "dataset/synthetic_control/synthetic_control_TEST"};
    // TODO: move experiments to a separate file/class
    //args = new String[] {"dataset/synthetic_control/synthetic_control_TRAIN",
    //        "dataset/synthetic_control/synthetic_control_TEST"};
    //args = new String[] {"/Users/ppo/Documents/Thesis/dataset/ElectricDevices/ElectricDevices_TRAIN",
    //        "/Users/ppo/Documents/Thesis/dataset/ElectricDevices/ElectricDevices_TEST"};
    //args = new String[] {"-n", "500", "-r", "50", "-a", "24", "-t", "64", "-l", "60", "-u", "62",
    //        "/Users/ppo/Documents/Thesis/dataset/BeetleFly/BeetleFly_TRAIN",
    //        "/Users/ppo/Documents/Thesis/dataset/BeetleFly/BeetleFly_TEST"};
    //args = new String[] {"/Users/ppo/Documents/Thesis/dataset/Adiac/Adiac_TRAIN",
    //        "/Users/ppo/Documents/Thesis/dataset/Adiac/Adiac_TEST"};
    //args = new String[] {"-n", "500", "-r", "500", "-a", "32", "-tw", "8", "-sw", "8",
    //        "/Users/ppo/Documents/Thesis/dataset/ItalyPowerDemand/ItalyPowerDemand_TRAIN",
    //        "/Users/ppo/Documents/Thesis/dataset/ItalyPowerDemand/ItalyPowerDemand_TEST"};

    // String s = "-r 10 -s 0.3 -m -w /Users/isak/Downloads/dataSets/Cricket/xleft.txt
    // /Users/isak/Downloads/dataSets/Cricket/xright.txt
    // /Users/isak/Downloads/dataSets/Cricket/yleft.txt
    // /Users/isak/Downloads/dataSets/Cricket/yright.txt
    // /Users/isak/Downloads/dataSets/Cricket/zleft.txt
    // /Users/isak/Downloads/dataSets/Cricket/zright.txt";
    // args = s.split(" ");
    Options options = new Options();

    options.addOption("n", "no-trees", true, "Number of trees");
    //options.addOption("l", "lower", true, "Lower shapelet size (fraction of length, e.g, 0.05)");
    //options.addOption("u", "upper", true, "Upper shapelet size (fraction of length, e.g, 0.8)");
    options.addOption("r", "sample", true, "Number of shapelets");
    options.addOption("p", "print-shapelets", false, "Print the shapelets of the forest");
    options.addOption("m", "multivariate", false, "The given dataset is in a multivariate format");
    options.addOption("c", "cv", true, "Combine datasets and run cross validation");
    options.addOption("w", "weird", false, "Weird mts-format");
    options.addOption("s", "split", true, "Combine datasets and use split validation");
    options.addOption("o", "optimize", false, "optimize the parameters using oob");
    options.addOption("d", "csv-delim", false, "Present the results as a comma separated list");
    options.addOption("a", "alphabet-size", true, "Alphabet size for the SAX conversion");
    options.addOption("t", "ts-wordlength", true, "Word length for the time series SAX convsersion");
    options.addOption("l", "lower", true, "Lower limit of word length for the shapelet SAX convsersion");
    options.addOption("u", "upper", true, "Upper limit of word length for the shapelet SAX convsersion");
    CommandLineParser parser = new DefaultParser();
    try {
      CommandLine cmd = parser.parse(options, args);
      int noTrees = Integer.parseInt(cmd.getOptionValue("n", "100"));
      //double lower = Double.parseDouble(cmd.getOptionValue("l", "0.025"));
      //double upper = Double.parseDouble(cmd.getOptionValue("u", "1"));
      int r = Integer.parseInt(cmd.getOptionValue("r", "10"));
      int alphabetSize = Integer.parseInt(cmd.getOptionValue("a", "16"));
      SaxOptions.setAlphabetSize(alphabetSize);
      int tsWordLength = Integer.parseInt(cmd.getOptionValue("t", "15"));
      SaxOptions.setTsWordLength(tsWordLength);
      // lower shapelet size can't be lower than 2 - possibly due to z-normalization?
      //int lowerWordLength = Integer.parseInt(cmd.getOptionValue("l", "2"));
      double lowerWordLength = Double.parseDouble(cmd.getOptionValue("l", "0"));
      SaxOptions.setLowerWordLength(lowerWordLength);
      //int upperWordLength = Integer.parseInt(cmd.getOptionValue("u", "15"));
      double upperWordLength = Double.parseDouble(cmd.getOptionValue("u", "1"));
      SaxOptions.setUpperWordLength(upperWordLength);
      SaxOptions.generateDistTable(alphabetSize);
      boolean print = cmd.hasOption("p");

      List<String> files = cmd.getArgList();
      if (files == null || files.isEmpty()) {
        //throw new RuntimeException("Training/testing data missing");
        files.add("dataset/synthetic_control/synthetic_control_TRAIN");
        files.add("dataset/synthetic_control/synthetic_control_TEST");
      }

      int testLength = 0;
      int testNumTs = 0;

      // Compute the minimum distance between the shapelet and the time series
      /*PatternDistance<MultivariateTimeSeries, MultivariateShapelet> patternDistance =
          new PatternDistance<MultivariateTimeSeries, MultivariateShapelet>() {
            private EarlyAbandonSlidingDistance distance = new EarlyAbandonSlidingDistance();

            public double computeDistance(MultivariateTimeSeries a, MultivariateShapelet b) {
              return distance.compute(a.getDimension(b.getDimension()), b.getShapelet());
            }
          };*/

      // Compute minimum SAX distance between the shapelet and the time series
      PatternDistance<MultivariateTimeSeries, MultivariateShapelet> patternDistance =
              new PatternDistance<MultivariateTimeSeries, MultivariateShapelet>() {
                private SlidingSAXDistance distance = new SlidingSAXDistance();

                @Override
                public double computeDistance(MultivariateTimeSeries a, MultivariateShapelet b) {
                  return distance.compute(a.getDimension(b.getDimension()), b.getShapelet());
                }
              };

      Pair<Input<MultivariateTimeSeries>, Output<Object>> train;
      ClassifierValidator<MultivariateTimeSeries, Object> validator;
      if (cmd.hasOption("c") || cmd.hasOption("s")) {
        Input<MultivariateTimeSeries> t = new ArrayInput<>();
        Output<Object> o = new ArrayOutput<>();
        if (cmd.hasOption("m") && cmd.hasOption("w")) {
          List<Pair<Input<MultivariateTimeSeries>, Output<Object>>> list = new ArrayList<>();
          for (String file : files) {
            list.add(readData(file));
          }
          t.addAll(getMultivariateTimeSeries(list));
          o.addAll(list.get(0).getSecond());
        } else if (cmd.hasOption("m")) {
          for (String file : files) {
            Pair<Input<MultivariateTimeSeries>, Output<Object>> data = readMtsData(file);
            t.addAll(data.getFirst());
            o.addAll(data.getSecond());
          }
        } else {
          for (String file : files) {
            Pair<Input<MultivariateTimeSeries>, Output<Object>> data = readData(file);
            t.addAll(data.getFirst());
            o.addAll(data.getSecond());
          }
        }
        ((ElementSwapper) (a, b) -> {
          MultivariateTimeSeries tmp = t.get(a);
          t.set(a, t.get(b));
          t.set(b, tmp);

          Object tmp2 = o.get(a);
          o.set(a, o.get(b));
          o.set(b, tmp2);
        }).permute(t.size());
        train = new Pair<>(t, o);
        if (cmd.hasOption("c")) {
          validator =
              ClassifierValidator.crossValidator(Integer.parseInt(cmd.getOptionValue("c", "10")));
        } else {
          validator = ClassifierValidator
              .splitValidator(Double.parseDouble(cmd.getOptionValue("s", "0.3")));
        }
      } else {
        Pair<Input<MultivariateTimeSeries>, Output<Object>> test;
        if (cmd.hasOption("m")) {
          if (files.size() == 2) {
            train = readMtsData(files.get(0));
            test = readMtsData(files.get(1));
          } else {
            List<Pair<Input<MultivariateTimeSeries>, Output<Object>>> lTest = new ArrayList<>();
            List<Pair<Input<MultivariateTimeSeries>, Output<Object>>> lTrain = new ArrayList<>();
            for (int i = 0; i < files.size() - 1; i += 2) {
              String trainFile = files.get(i);
              String testFile = files.get(i + 1);
              Pair<Input<MultivariateTimeSeries>, Output<Object>> pTrain = readData(trainFile);
              Pair<Input<MultivariateTimeSeries>, Output<Object>> pTest = readData(testFile);
              lTrain.add(pTrain);
              lTest.add(pTest);
            }
            Input<MultivariateTimeSeries> testIn = getMultivariateTimeSeries(lTest);
            Input<MultivariateTimeSeries> trainIn = getMultivariateTimeSeries(lTrain);
            train = new Pair<>(trainIn, lTrain.get(0).getSecond());
            test = new Pair<>(testIn, lTest.get(0).getSecond());
          }
        } else {
          train = readData(files.get(0));
          test = readData(files.get(1));
          testNumTs = test.getKey().size();
          testLength = test.getKey().get(0).getDimension(0).size();
        }
        // validator = ClassifierValidator.holdoutValidator(test.getFirst(), test.getSecond());
        validator = new ClassifierValidator<MultivariateTimeSeries, Object>(
            Collections.singleton(ClassifierEvaluator.getInstance()),
            new Partitioner<MultivariateTimeSeries, Object>() {
              @Override
              public Collection<Partition<MultivariateTimeSeries, Object>> partition(
                  Input<? extends MultivariateTimeSeries> x, Output<?> y) {
                return Collections.singleton(new Partition<>(Inputs.unmodifiableInput(x),
                    Inputs.unmodifiableInput(test.getFirst()), Outputs.unmodifiableOutput(y),
                    Outputs.unmodifiableOutput(test.getSecond())));
              }
            }) {

          @Override
          protected long preFit() {
            return getTotalThreadCPUTime();
          }

          @Override
          protected double postFit(long start) {
            return getTotalThreadCPUTime()/1e6;
          }

          @Override
          protected long prePredict() {
            return getTotalThreadCPUTime();
          }

          @Override
          protected double postPredict(long start) {
            return getTotalThreadCPUTime()/1e6;
          }
        };
      }
      validator.add(EnsembleEvaluator.getInstance());
      List<MultivariateShapelet> shapelets = new ArrayList<>();
      if (print) {
        validator.add(ctx -> {
          RandomPatternForest<MultivariateTimeSeries, Object> f =
              (RandomPatternForest<MultivariateTimeSeries, Object>) ctx.getPredictor();
          for (ProbabilityEstimator<MultivariateTimeSeries, Object> m : f.getEnsembleMembers()) {
            PatternTree<MultivariateTimeSeries, Object> t =
                (PatternTree<MultivariateTimeSeries, Object>) m;
            extractShapelets(shapelets, t.getRootNode());
          }
        });
      }
      Result<Object> result = null;
      double totalFitTime = 0;
      double totalPredictTime = 0;
      double[] minLu = null;
      int minR = -1;
      if (cmd.hasOption("o")) {
        //@formatter:off
        /*double[][] lowerUpper = {
            {0.025, 1},
            {0.025, 0.1},
            {0.025, 0.2},
            {0.025, 0.3},
            {0.025, 0.4},
            {0.2, 0.5},
            {0.3, 0.6},
            {0.6, 1},
            {0.7, 1},
            {0.8, 1},
            {0.9, 1}
        };
        //@formatter:on

        int[] ropt = {1, 10,50,100, 500, -1};
        double minOobError = Double.POSITIVE_INFINITY;
        for (double[] lu : lowerUpper) {
          for (int rv : ropt) {
            if (rv == -1) {
              int m = train.getFirst().get(0).getDimension(0).size();
              int d = train.getFirst().get(0).dimensions();
              rv = (int) Math.sqrt(m * d * (m * d + 1) / 2);
            }
            PatternFactory<MultivariateTimeSeries, MultivariateShapelet> patternFactory =
                getPatternFactory(lu[0], lu[1]);

            RandomPatternForest.Learner<MultivariateTimeSeries, Object> rsf =
                new RandomPatternForest.Learner<>(patternFactory, patternDistance, noTrees);
            rsf.set(PatternTree.PATTERN_COUNT, rv);
            Result<Object> res = validator.test(rsf, train.getFirst(), train.getSecond());
            totalFitTime += res.getFitTime();
            totalPredictTime += res.getPredictTime();
            Series measures = res.getMeasures().reduce(Series::mean);
            if (measures.getDouble("oobError") < minOobError) {
              result = res;
              minLu = lu;
              minR = rv;
              minOobError = measures.getDouble("oobError");
            }
          }
        }*/
      } else {
        PatternFactory<MultivariateTimeSeries, MultivariateShapelet> patternFactory =
            getPatternFactory(lowerWordLength, upperWordLength, tsWordLength);

        RandomPatternForest.Learner<MultivariateTimeSeries, Object> rsf =
            new RandomPatternForest.Learner<>(patternFactory, patternDistance, noTrees);
        rsf.set(PatternTree.PATTERN_COUNT, r);
        result = validator.test(rsf, train.getFirst(), train.getSecond());
      }

      if (print) {
        //shapelets.sort((a, b) -> Integer.compare(a.getShapelet().size(), b.getShapelet().size()));
        System.out.println("Shapelet size distribution");
        System.out.println("**************************");
        Map<Integer, Integer> shapeletCount = new HashMap<Integer, Integer>();
        for (int i = 0; i < shapelets.size(); i++) {
          //System.out.print(i + "\t");
          Shapelet shapelet = shapelets.get(i).getShapelet();
          /*for (int j = 0; j < shapelet.size(); j++) {
            System.out.print(shapelet.getDouble(j) + " ");
          }*/
          int l = ((NormalizedShapelet) shapelets.get(i).getShapelet()).getNormalizedSaxWord().length;
          if (shapeletCount.containsKey(l)) {
              shapeletCount.put(l, shapeletCount.get(l)+1);
          } else {
              shapeletCount.put(l, 1);
          }
          //System.out.println(shapelet.size());
          //System.out.println(shapelet.size() + "\t");
          //System.out.println(((NormalizedShapelet) shapelets.get(i).getShapelet()).getNormalizedSaxWord().length);
        }
        for (Map.Entry<Integer, Integer> entry : shapeletCount.entrySet()) {
          int key = entry.getKey();
          int value = entry.getValue();
          System.out.println(key + "\t" + value);
        }
        System.out.println("");
      }

      Series measures = result.getMeasures().reduce(Series::mean);
      if (cmd.hasOption("d")) {
        // Format as csv
        // accuracy, aucRoc, totalFitTime, totalPredictTime, lu, r
        //System.out.println(measures.get("accuracy") + "," + measures.get("aucRoc") + "," + totalFitTime + "," + totalPredictTime + "," + Arrays.toString(minLu) + "," + minR);
        System.out.printf("%d;%d;%d;%s;%s;%s;%s;%s%n", noTrees, r, alphabetSize, tsWordLength, lowerWordLength, upperWordLength, measures.get("accuracy").toString(), (Double.toString(result.getPredictTime())));

      } else {
        System.out.println("Parameters");
        System.out.println("**********");
        for (Option o : cmd.getOptions()) {
          System.out.printf("%s:  %s%n", o.getLongOpt(), o.getValue("[default]"));
        }
        if (files.size() == 2) {
          System.out.printf("Training data '%s'%n", files.get(0));
          System.out.printf("Training data time series number: %d%n", train.getKey().size());
          System.out.printf("Training data time series length: %d%n", train.getKey().get(0).getDimension(0).size());
          System.out.printf("Testing data  '%s'%n", files.get(1));
          System.out.printf("Testing data time series number: %d%n", testNumTs);
          System.out.printf("Testing data time series length: %d%n", testLength);

        }
        System.out.println(" ---- ---- ---- ---- ");
        System.out.printf("Number of trees: %d%n", noTrees);
        System.out.printf("Number of shapelets per node: %d%n", r);
        System.out.printf("Timeseries SAX word length: %d%n", tsWordLength);
        System.out.printf("Lower limit of shapelet SAX word length: %.2f%n", lowerWordLength);
        System.out.printf("Upper limit of shapelet SAX word length: %.2f%n", upperWordLength);
        System.out.printf("SAX alphabet size: %d%n", alphabetSize);
        System.out.println(" ---- ---- ---- ---- ");

        System.out.println("\nResults");
        System.out.println("*******");
        for (Object key : measures.index()) {
          System.out.printf("%s:  %.4f%n", key, measures.getDouble(key));
        }
        System.out.println(" ---- ---- ---- ---- ");
        System.out.printf("Runtime (training)  %.2f ms (CPU TIME)%n", result.getFitTime());
        System.out.printf("Runtime (testing)   %.2f ms (CPU TIME)%n", result.getPredictTime());
      }
    } catch (Exception e) {
      HelpFormatter formatter = new HelpFormatter();
      e.printStackTrace();
      formatter.printHelp("rsfcmd.jar [OPTIONS] trainFile testFile", options);
    }
  }

  private static PatternFactory<MultivariateTimeSeries, MultivariateShapelet> getPatternFactory(
      final double lowerShapeletSAXLength, final double upperShapeletSAXLength, final int tsSAXLength) {
    return new PatternFactory<MultivariateTimeSeries, MultivariateShapelet>() {

      /**
       * @param inputs the input dataset
       * @param classSet the inputs included in the current bootstrap.
       * @return a shapelet
       */
      public MultivariateShapelet createPattern(Input<? extends MultivariateTimeSeries> inputs,
          ClassSet classSet) {
        MultivariateTimeSeries mts =
                inputs.get(classSet.getRandomSample().getRandomExample().getIndex());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int randomDim = random.nextInt(mts.dimensions());
        TimeSeries uts = mts.getDimension(randomDim);
        int timeSeriesLength = uts.size();
        int upper = (int) Math.round(tsSAXLength * upperShapeletSAXLength);
        int lower = (int) Math.round(tsSAXLength * lowerShapeletSAXLength);
        if (lower < 2) {
          lower = 2;
        }

        /*if (Math.addExact(upper, lower) > timeSeriesLength) {
          upper = timeSeriesLength - lower;
        }
        if (lower == upper) {
          upper -= 2;
        }
        if (upper < 1) {
          return null;
        }

        int length = ThreadLocalRandom.current().nextInt(upper) + lower;*/
        double segmentSize = (double) timeSeriesLength / tsSAXLength;
        int randShapeletSAXLength = (lower + ThreadLocalRandom.current().nextInt(upper - lower + 1));
        int length = (int) Math.round(segmentSize * randShapeletSAXLength);
        int start = 0;
        if (timeSeriesLength != length) {
          start = ThreadLocalRandom.current().nextInt(timeSeriesLength - length);
        }
        return new MultivariateShapelet(randomDim,
            new IndexSortedNormalizedShapelet(start, length, uts, randShapeletSAXLength));
      }
    };
  }

  private static Input<MultivariateTimeSeries> getMultivariateTimeSeries(
      List<Pair<Input<MultivariateTimeSeries>, Output<Object>>> data) {
    Input<MultivariateTimeSeries> input = new ArrayInput<>();
    int n = data.get(0).getFirst().size();
    for (int i = 0; i < n; i++) {
      TimeSeries[] trainSeries = new TimeSeries[data.size()];
      for (int j = 0; j < data.size(); j++) {
        trainSeries[j] = data.get(j).getFirst().get(i).getDimension(0);
      }
      input.add(new MultivariateTimeSeries(trainSeries));
    }
    return input;
  }

  private static void extractShapelets(List<MultivariateShapelet> shapelets,
      TreeNode<MultivariateTimeSeries, ?> node) {
    if (node instanceof TreeLeaf) {
      return;
    }

    @SuppressWarnings("unchecked")
    TreeBranch<MultivariateTimeSeries, PatternTree.Threshold<MultivariateShapelet>> b =
        (TreeBranch<MultivariateTimeSeries, PatternTree.Threshold<MultivariateShapelet>>) node;
    shapelets.add(b.getThreshold().getPattern());
    extractShapelets(shapelets, b.getLeft());
    extractShapelets(shapelets, b.getRight());
  }

  private static Pair<Input<MultivariateTimeSeries>, Output<Object>> readData(String filePath)
      throws IOException {
    // Construct the input and output variables
    Input<MultivariateTimeSeries> input = new ArrayInput<>();
    Output<Object> output = new ArrayOutput<>();

    // Read the file
    List<String> data = Files.readAllLines(Paths.get(filePath));
    // Collections.shuffle(data, ThreadLocalRandom.current());
    for (String line : data) {
      String[] split = line.trim().split("\\s+");
      output.add((int) Double.parseDouble(split[0]));

      TimeSeries timeSeries = getTimeSeries(1, split);
      input.add(new MultivariateTimeSeries(timeSeries));
    }
    return new Pair<>(input, output);
  }

  private static TimeSeries getTimeSeries(int start, String[] split) {
    double[] ts = new double[split.length - start];
    for (int i = start; i < split.length; i++) {
      ts[i - start] = Double.parseDouble(split[i]);
    }
    return TimeSeries.of(ts);
  }

  private static Pair<Input<MultivariateTimeSeries>, Output<Object>> readMtsData(String folder)
      throws IOException {
    Input<MultivariateTimeSeries> input = new ArrayInput<>();
    Output<Object> output = new ArrayOutput<>();

    String data = new String(Files.readAllBytes(Paths.get(folder, "classes.dat")));
    Collections.addAll(output, data.trim().split(","));

    List<Path> files = new ArrayList<>();
    Files.newDirectoryStream(Paths.get(folder, "data")).forEach(files::add);
    files.sort((a, b) -> getNameWithoutExt(a).compareTo(getNameWithoutExt(b)));

    for (Path exampleFile : files) {
      List<String> lines = Files.readAllLines(exampleFile);
      TimeSeries[] mts = new TimeSeries[lines.size()];
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        String[] split = line.trim().split(",");
        mts[i] = getTimeSeries(0, split);
      }
      input.add(new MultivariateTimeSeries(mts));
    }
    return new Pair<>(input, output);
  }

  private static Integer getNameWithoutExt(Path a) {
    String name = a.getFileName().toString();
    return Integer.parseInt(name.split("\\.")[0]);
  }

}
