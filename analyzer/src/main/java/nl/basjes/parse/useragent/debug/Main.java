/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2017 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent.debug;

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.analyze.Analyzer;
import nl.basjes.parse.useragent.analyze.MatcherAction;
import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener;
import org.antlr.v4.runtime.tree.ParseTree;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import static nl.basjes.parse.useragent.debug.Main.OutputFormat.CSV;
import static nl.basjes.parse.useragent.debug.Main.OutputFormat.JSON;
import static nl.basjes.parse.useragent.debug.Main.OutputFormat.YAML;

public final class Main {
    private Main() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    enum OutputFormat {
        CSV, JSON, YAML
    }

    private static void printHeader(OutputFormat outputFormat, UserAgentAnalyzer uaa) {
        switch (outputFormat) {
            case CSV:
                for (String field : uaa.getAllPossibleFieldNamesSorted()) {
                    System.out.print(field);
                    System.out.print("\t");
                }
                System.out.println("Useragent");
                break;
            default:
                break;
        }
    }

    private static void printAgent(OutputFormat outputFormat, UserAgentAnalyzer uaa, UserAgent agent) {
        switch (outputFormat) {
            case CSV:
                for (String field : uaa.getAllPossibleFieldNamesSorted()) {
                    String value = agent.getValue(field);
                    if (value != null) {
                        System.out.print(value);
                    }
                    System.out.print("\t");
                }
                System.out.println(agent.getUserAgentString());
            case JSON:
                System.out.println(agent.toJson());
                break;
            case YAML:
                System.out.println(agent.toYamlTestCase());
                break;
            default:
        }
    }

    public static void main(String[] args) throws IOException {
        int returnValue = 0;
        final CommandOptions commandlineOptions = new CommandOptions();
        final CmdLineParser parser = new CmdLineParser(commandlineOptions);
        try {
            parser.parseArgument(args);

            if (commandlineOptions.useragent == null && commandlineOptions.inFile == null) {
                //noinspection deprecation
                throw new CmdLineException(parser, "No input specified.");
            }

            OutputFormat outputFormat = YAML;
            if (commandlineOptions.csvFormat) {
                outputFormat = CSV;
            } else {
                if (commandlineOptions.jsonFormat) {
                    outputFormat = JSON;
                }
            }

            UserAgentAnalyzerTester uaa = new UserAgentAnalyzerTester();
            uaa.initialize();
            UserAgentTreeFlattener flattenPrinter = new UserAgentTreeFlattener(new FlattenPrinter());
            uaa.setVerbose(commandlineOptions.debug);

            printHeader(outputFormat, uaa);

            if (commandlineOptions.useragent != null) {
                UserAgent agent = uaa.parse(commandlineOptions.useragent);
                printAgent(outputFormat, uaa, agent);
                return;
            }

            // Open the file
            FileInputStream fstream = new FileInputStream(commandlineOptions.inFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(fstream));

            String strLine;

            long ambiguities    = 0;
            long syntaxErrors   = 0;

            long linesTotal   = 0;
            long hitsTotal    = 0;
            long linesOk      = 0;
            long hitsOk       = 0;
            long linesMatched = 0;
            long hitsMatched  = 0;
            long start = System.nanoTime();
            LOG.info("Start @ {}", start);

            long segmentStartTime = start;
            long segmentStartLines = linesTotal;

            //Read File Line By Line
            while ((strLine = br.readLine()) != null) {
                if (strLine.startsWith(" ") || strLine.startsWith("#") || strLine.isEmpty()) {
                    continue;
                }

                long hits = 1;
                String agentStr = strLine;

                if (strLine.contains("\t")) {
                    String[] parts = strLine.split("\t", 2);
                    hits = Long.parseLong(parts[0]);
                    agentStr = parts[2];
                }

                if (commandlineOptions.fullFlatten) {
                    flattenPrinter.parse(agentStr);
                    continue;
                }

                if (commandlineOptions.matchedFlatten) {
                    for (MatcherAction.Match match : uaa.getUsedMatches(new UserAgent(agentStr))) {
                        System.out.println(match.getKey() + " " + match.getValue());
                    }
                    continue;
                }

                UserAgent agent = uaa.parse(agentStr);

                boolean hasBad = false;
                for (String field : UserAgent.STANDARD_FIELDS) {
                    if (agent.getConfidence(field) < 0) {
                        hasBad = true;
                        break;
                    }
                }

                linesTotal++;
                hitsTotal += hits;

                if (agent.hasSyntaxError()) {
                    if (outputFormat == YAML) {
                        System.out.println("# Syntax error: " + agentStr);
                    }
                } else {
                    linesOk++;
                    hitsOk += hits;
                }

                if (!hasBad) {
                    linesMatched++;
                    hitsMatched += hits;
                }

                if (agent.hasAmbiguity()) {
                    ambiguities++;
                }
                if (agent.hasSyntaxError()) {
                    syntaxErrors++;
                }

                if (linesTotal % 1000 == 0) {
                    long nowTime = System.nanoTime();
                    long speed = (1000000000L*(linesTotal-segmentStartLines))/(nowTime-segmentStartTime);
                    System.err.println("Lines = "+linesTotal + " (A="+ambiguities+" S="+syntaxErrors+")  Speed = " + speed + "/sec.");
                    segmentStartTime = nowTime;
                    segmentStartLines = linesTotal;
                    ambiguities=0;
                    syntaxErrors=0;
                }

                if (commandlineOptions.outputOnlyBadResults) {
                    if (hasBad) {
                        continue;
                    }
                }

                printAgent(outputFormat, uaa, agent);
            }

            //Close the input stream
            br.close();
            long stop = System.nanoTime();
            LOG.info("Stop  @ {}", stop);

            LOG.info("-------------------------------------------------------------");
            LOG.info("Performance: {} in {} sec --> {}/sec", linesTotal, (stop-start)/1000000000L, (1000000000L*linesTotal)/(stop-start));
            LOG.info("-------------------------------------------------------------");
            LOG.info("Parse results of {} lines", linesTotal);
            LOG.info("Parsed without error: {} (={}%)", linesOk, 100.0*(double)linesOk/(double)linesTotal);
            LOG.info("Fully matched       : {} (={}%)", linesMatched, 100.0*(double)linesMatched/(double)linesTotal);
            LOG.info("-------------------------------------------------------------");
            LOG.info("Parse results of {} hits", hitsTotal);
            LOG.info("Parsed without error: {} (={}%)", hitsOk, 100.0*(double)hitsOk/(double)hitsTotal);
            LOG.info("Fully matched       : {} (={}%)", hitsMatched, 100.0*(double)hitsMatched/(double)hitsTotal);
            LOG.info("-------------------------------------------------------------");

        } catch (final CmdLineException e) {
            UserAgentAnalyzer.logVersion();
            LOG.error("Errors: " + e.getMessage());
            LOG.error("");
            System.err.println("Usage: java jar <jar containing this class> <options>");
            parser.printUsage(System.err);
            returnValue = 1;
        } catch (final Exception e) {
            LOG.error("IOException:" + e);
            returnValue = 1;
        }
        System.exit(returnValue);
    }

    @SuppressWarnings({"PMD.ImmutableField", "CanBeFinal", "unused"})
    private static class CommandOptions {
        @Option(name = "-ua", usage = "A single useragent string", forbids = {"-in"})
        private String useragent = null;

        @Option(name = "-in", usage = "Location of input file", forbids = {"-ua"})
        private String inFile = null;

//        @Option(name = "-testAll", usage = "Run the tests against all built in testcases", required = false)
//        private boolean testAll = false;

        @Option(name = "-yaml", usage = "Output in yaml testcase format", forbids = {"-csv", "-json"})
        private boolean yamlFormat = false;

        @Option(name = "-csv", usage = "Output in csv format", forbids = {"-yaml", "-json"})
        private boolean csvFormat = false;

        @Option(name = "-json", usage = "Output in json format", forbids = {"-yaml", "-csv"})
        private boolean jsonFormat = false;

        @Option(name = "-bad", usage = "Output only cases that have a problem")
        private boolean outputOnlyBadResults = false;

        @Option(name = "-debug", usage = "Set to enable debugging.")
        private boolean debug = false;

//        @Option(name = "-stats", usage = "Set to enable statistics.", required = false)
//        private boolean stats = false;

        @Option(name = "-fullFlatten", usage = "Set to flatten each parsed agent string.")
        private boolean fullFlatten = false;

        @Option(name = "-matchedFlatten", usage = "Set to get the flattened values that were relevant for the Matchers.")
        private boolean matchedFlatten = false;

    }

    public static class FlattenPrinter extends Analyzer {

        @Override
        public void inform(String path, String value, ParseTree ctx) {
            System.out.println(path); // + " = " + value);
        }

        @Override
        public void informMeAbout(MatcherAction matcherAction, String keyPattern) {

        }
    }

}
