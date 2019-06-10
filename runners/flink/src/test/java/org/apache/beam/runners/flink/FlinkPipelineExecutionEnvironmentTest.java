/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Charsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FlinkPipelineExecutionEnvironment}. */
@RunWith(JUnit4.class)
public class FlinkPipelineExecutionEnvironmentTest implements Serializable {

  @Test
  public void shouldRecognizeAndTranslateStreamingPipeline() {
    FlinkPipelineOptions options = PipelineOptionsFactory.as(FlinkPipelineOptions.class);
    options.setRunner(TestFlinkRunner.class);
    options.setFlinkMaster("[auto]");

    FlinkPipelineExecutionEnvironment flinkEnv = new FlinkPipelineExecutionEnvironment(options);
    Pipeline pipeline = Pipeline.create();

    pipeline
        .apply(GenerateSequence.from(0).withRate(1, Duration.standardSeconds(1)))
        .apply(
            ParDo.of(
                new DoFn<Long, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) throws Exception {
                    c.output(Long.toString(c.element()));
                  }
                }))
        .apply(Window.into(FixedWindows.of(Duration.standardHours(1))))
        .apply(TextIO.write().withNumShards(1).withWindowedWrites().to("/dummy/path"));

    flinkEnv.translate(pipeline);

    // no exception should be thrown
  }

  @Test
  public void shouldLogWarningWhenCheckpointingIsDisabled() {
    Pipeline pipeline = Pipeline.create();
    pipeline.getOptions().setRunner(TestFlinkRunner.class);

    pipeline
        // Add an UnboundedSource to check for the warning if checkpointing is disabled
        .apply(GenerateSequence.from(0))
        .apply(
            ParDo.of(
                new DoFn<Long, Void>() {
                  @ProcessElement
                  public void processElement(ProcessContext ctx) {
                    throw new RuntimeException("Failing here is ok.");
                  }
                }));

    final PrintStream oldErr = System.err;
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    PrintStream replacementStdErr = new PrintStream(byteArrayOutputStream);
    try {
      System.setErr(replacementStdErr);
      // Run pipeline and fail during execution
      pipeline.run();
      fail("Should have failed");
    } catch (Exception e) {
      // We want to fail here
    } finally {
      System.setErr(oldErr);
    }
    replacementStdErr.flush();
    assertThat(
        new String(byteArrayOutputStream.toByteArray(), Charsets.UTF_8),
        containsString(
            "UnboundedSources present which rely on checkpointing, but checkpointing is disabled."));
  }
}
