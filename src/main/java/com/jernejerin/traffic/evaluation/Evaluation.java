package com.jernejerin.traffic.evaluation;

import com.jernejerin.traffic.architectures.*;
import com.jernejerin.traffic.helper.MedianOfStream;
import com.jernejerin.traffic.helper.PollingDriver;
import com.jernejerin.traffic.helper.SimpleCellRefGenerator;
import com.jernejerin.traffic.helper.TripOperations;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.apache.logging.log4j.core.util.FileUtils;
import org.jxls.area.XlsArea;
import org.jxls.command.Command;
import org.jxls.command.EachCommand;
import org.jxls.common.AreaRef;
import org.jxls.common.CellRef;
import org.jxls.common.Context;
import org.jxls.transform.Transformer;
import org.jxls.util.TransformerFactory;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contains evaluation of different architectures. The evaluation is done on the
 * following attributes:
 * <ul>
 *     <li> delay - the delay for writing each top 10. This is the average value of delays.
 *     <li> total duration of execution - The total time it took to complete.
 *
 * @author Jernej Jerin
 */
public class Evaluation {
    public static void main(String[] args) throws Exception {
        ArchitectureBuilder builder = new ArchitectureBuilder();

        // set host and port from command line options
        builder.setOptionsCmd(args);

        // enumerate all the locally running Java processes and get the process that is running the Evaluation program
        Optional<VirtualMachineDescriptor> evaluationProgram = VirtualMachine.list().stream().
                filter(descriptor -> descriptor.displayName().contains("Evaluation")).findFirst();

        if (!evaluationProgram.isPresent()) {
            System.out.println("Virtual machine descriptor not present.");
            System.exit(2);
        }

        // attach to the virtual machine given the pid from the descriptor
        VirtualMachine vm = VirtualMachine.attach(evaluationProgram.get().id());
        if (!(vm instanceof HotSpotVirtualMachine)) {
            System.out.println("Only works on HotSpot!");
            System.exit(3);
        }

        // to invoke diagnostic commands, we need to cast our VirtualMachine to a HotSpotVirtualMachine
        HotSpotVirtualMachine hsvm = (HotSpotVirtualMachine) vm;

        // TODO (Jernej Jerin): Execute executeJCmd method to start the Flight Recorder
        // https://community.oracle.com/thread/3676275
//        hsvm.executeJCmd("Thread.print");

        // a list of architectures to evaluate
        List<Architecture> architectures = new ArrayList<>();
        architectures.add(new EDAPrimer(builder));

        // number of times to run the evaluation for each architecture
        int numTimes = 10;

        // variable for storing measurements of the architecture
        List<Measurement> measurements = new ArrayList<>(architectures.size());

        // run each architecture, which generates output in diagnostics, query and reports
        for (Architecture architecture : architectures) {
            // HERE IS THE EVALUATION CALL
            Measurement measurement = evaluate(architecture, numTimes);
            measurement.name = architecture.getClass().getSimpleName();
            measurements.add(measurement);
        }

        // print the results on to the screen
        for (Measurement measurement : measurements) {
            System.out.println(measurement.name);
            printResult(measurement);
        }

        // generate Excel report
        // first we need a template
        InputStream is = Evaluation.class.getResourceAsStream("/com/jernejerin/Measurements_template.xlsx");

        // write the generated report to reports folder
        OutputStream os = new FileOutputStream("output/reports/Measurements.xlsx");

        // create a transformer
        Transformer transformer = TransformerFactory.createTransformer(is, os);

        // save the root area
        XlsArea xlsArea = new XlsArea("Template!A1:Q12", transformer);
        XlsArea measurementArea = new XlsArea("Template!A1:Q12", transformer);

        // creating each command for measurements providing custom cell reference generator instance
        EachCommand measurementEachCommand = new EachCommand("measurement", "measurements", measurementArea,
                new SimpleCellRefGenerator());
        XlsArea runMeasurementArea = new XlsArea("Template!A12:Q12", transformer);

        // each command for run measurements
        Command runMeasurementEachCommand = new EachCommand("runMeasurement", "measurement.runMeasurements",
                runMeasurementArea);
        measurementArea.addCommand(new AreaRef("Template!A12:Q12"), runMeasurementEachCommand);
        xlsArea.addCommand(new AreaRef("Template!A1:Q12"), measurementEachCommand);

        // get the context for binding variables with template placeholders
        Context context = transformer.createInitialContext();

        // bind variables
        context.putVar("measurements", measurements);
        context.putVar("runMeasurements", measurements.get(0).runMeasurements);

        // apply transformation to sheet named Result
        xlsArea.applyAt(new CellRef("Result!A1"), context);
        transformer.write();
        is.close();
        os.close();
    }

    /**
     * Evaluate the passed architecture multiple times. Before the evaluation, run the
     * solution to cache values. The subsequent results should therefore not be impacted as much
     * by cache misses.
     *
     * @param architecture the architecture to evaluate
     * @param numTimes how many number of times to evaluate the solution
     * @return a measurement containing all per run measurements
     * @throws InterruptedException
     * @throws IOException
     */
    public static Measurement evaluate(Architecture architecture,
            int numTimes) throws InterruptedException, IOException {
        MedianOfStream<Long> medianDuration = new MedianOfStream<>();
        MedianOfStream<Double> medianDelayQuery1 = new MedianOfStream<>();
        MedianOfStream<Double> medianDelayQuery2 = new MedianOfStream<>();

        // holds the result for each evaluation
        List<RunMeasurement> results = new ArrayList<>(numTimes);

        //  run once before taking measurements to avoid taking into account cache misses
        architecture.setFileNameQuery1Output("output/query/" + architecture.getClass().getSimpleName() + "_query1_cache.txt");
        architecture.setFileNameQuery2Output("output/query/" + architecture.getClass().getSimpleName() + "_query2_cache.txt");
        architecture.run();

        for (int i = 0; i < numTimes; i++) {
            architecture.setFileNameQuery1Output("output/query/" + architecture.getClass().getSimpleName() + "_query1_" + i + ".txt");
            architecture.setFileNameQuery2Output("output/query/" + architecture.getClass().getSimpleName() + "_query2_" + i + ".txt");

            // TODO (Jernej Jerin): Trigger Java Mission Control Flight Recorder
            long duration = architecture.run();

            // query 1 and query 2 average
            double averageDelayQuery1 = getAverage(architecture.getFileQuery1().toPath());
            double averageDelayQuery2 = getAverage(architecture.getFileQuery2().toPath());

            // save the duration to median
            medianDuration.addNumberToStream(duration);

            // compute the average delay and save it to median
            medianDelayQuery1.addNumberToStream(averageDelayQuery1);
            medianDelayQuery2.addNumberToStream(averageDelayQuery2);

            // TODO (Jernej Jerin): add max, min values for buffer size, CPU and heap memory
            results.add(new RunMeasurement(i, duration, new MaxMinAverageMeasurement<>
                    (averageDelayQuery1, getMin(architecture.getFileQuery1().toPath()),
                            getMax(architecture.getFileQuery1().toPath())), new MaxMinAverageMeasurement<>
                    (averageDelayQuery2, getMin(architecture.getFileQuery2().toPath()),
                            getMax(architecture.getFileQuery2().toPath())), new MaxMinAverageMeasurement<>
                    (0d, 0l, 0l), new MaxMinAverageMeasurement<>
                    (0d, 0l, 0l), new MaxMinAverageMeasurement<>
                    (0d, 0l, 0l)));

            // clear output files and truncate db
            clearOutput();
            clearDB("trip");
            clearDB("tripchangetop10");
        }
        // TODO (Jernej Jerin): add median values for buffer size, CPU and heap memory
        return new Measurement(numTimes, architecture.getClass().getSimpleName(), medianDuration.getMedian(),
                medianDelayQuery1.getMedian(), 0d, 0, 0d, 0d, results);
    }

    /**
     * Get the average of the delay values in output files for query 1 and 2.
     *
     * @param filePath the file path of the file user wants to parse
     * @return the average of delays
     * @throws IOException
     */
    public static double getAverage(Path filePath) throws IOException {
        return Files.lines(filePath)
                .map(line -> line.split(","))
                .mapToLong(splits -> Long.parseLong(splits[splits.length - 1]))
                .average().getAsDouble();
    }

    /**
     * Get the minimum of the delay values in output files for query 1 and 2.
     *
     * @param filePath the file path of the file user wants to parse
     * @return the minimum of delays
     * @throws IOException
     */
    public static long getMin(Path filePath) throws IOException {
        return Files.lines(filePath)
                .map(line -> line.split(","))
                .mapToLong(splits -> Long.parseLong(splits[splits.length - 1]))
                .min().getAsLong();
    }

    /**
     * Get the max of the delay values in output files for query 1 and 2.
     *
     * @param filePath the file path of the file user wants to parse
     * @return the maximum of delays
     * @throws IOException
     */
    public static long getMax(Path filePath) throws IOException {
        return Files.lines(filePath)
                .map(line -> line.split(","))
                .mapToLong(splits -> Long.parseLong(splits[splits.length - 1]))
                .max().getAsLong();
    }

    /**
     * Output formatted results. First three lines are median values of multiple runs.
     * Then come lines consisting of result for execution time and average delay per each run.
     *
     * @param measurement the measurement to output
     */
    public static void printResult(Measurement measurement) {
        System.out.format("%20s%25s%n", "Median execution time: ", measurement.medianExecutionTime + " ms");
        System.out.format("%20s%25s%n", "Median delay query 1: ", measurement.medianDelayQuery1 + " ms");
        System.out.format("%20s%25s%n", "Median delay query 2: ", measurement.medianDelayQuery2 + " ms");
        System.out.format("%20s%25s%n", "Median buffer size: ", measurement.medianBufferSize);
        System.out.format("%20s%25s%n", "Median CPU: ", measurement.medianCPU + " %");
        System.out.format("%20s%25s%n", "Median heap memory: ", measurement.medianCPU + " MiB");

        System.out.format("%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s%30s30s%30s%n", "Run Id",
                "Execution time", "Max query 1",
                "Min query 1", "Average query 1", "Max query 2", "Min query 2", "Average query 2",
                "Max buffer size", "Min buffer size", "Average buffer size",
                "Max CPU", "Min CPU", "Average CPU", "Max heap memory", "Min heap memory", "Average heap memory");
        for (RunMeasurement runMeasurement : measurement.runMeasurements) {
            System.out.format("%30d%30d", runMeasurement.id, runMeasurement.executionTime);
            System.out.format("%30d%30d%30f", runMeasurement.query1.max, runMeasurement.query1.min,
                    runMeasurement.query1.average);
            System.out.format("%30d%30d%30f", runMeasurement.query2.max, runMeasurement.query2.min,
                    runMeasurement.query2.average);
            System.out.format("%30d%30d%30f", runMeasurement.bufferSize.max, runMeasurement.bufferSize.min,
                    runMeasurement.bufferSize.average);
            System.out.format("%30d%30d%30f", runMeasurement.cpu.max, runMeasurement.cpu.min,
                    runMeasurement.cpu.average);
            System.out.format("%30d%30d%30f%n", runMeasurement.heapMemory.max, runMeasurement.heapMemory.min,
                    runMeasurement.heapMemory.average);
        }
    }

    /**
     * Clears the output folder of generated output.
     */
    public static void clearOutput() {
        File dir = new File("output/query/");
        for (File file : dir.listFiles())
            file.delete();
    }

    /**
     * Truncates MySql DB.
     *
     * @param table table to truncate
     */
    public static void clearDB(String table) {
        TripOperations.truncateTable(table);
    }
}
