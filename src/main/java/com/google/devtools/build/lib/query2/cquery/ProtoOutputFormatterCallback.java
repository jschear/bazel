// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.query2.cquery;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeFormatter;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.QueryResult;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.query2.query.output.ProtoOutputFormatter;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.List;

/** Proto output formatter for cquery results. */
class ProtoOutputFormatterCallback extends CqueryThreadsafeCallback {

  /** Defines the types of proto output this class can handle. */
  public enum OutputType {
    BINARY("proto"),
    TEXT("textproto"),
    JSON("jsonproto");

    private final String formatName;

    OutputType(String formatName) {
      this.formatName = formatName;
    }

    public String formatName() {
      return formatName;
    }
  }

  private final OutputType outputType;
  private final AspectResolver resolver;
  private final SkyframeExecutor skyframeExecutor;
  private final JsonFormat.Printer jsonPrinter = JsonFormat.printer();

  private AnalysisProtosV2.CqueryResult.Builder protoResult;

  private KeyedConfiguredTarget currentTarget;

  ProtoOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<KeyedConfiguredTarget> accessor,
      AspectResolver resolver,
      OutputType outputType) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
    this.outputType = outputType;
    this.skyframeExecutor = skyframeExecutor;
    this.resolver = resolver;
  }

  @Override
  public void start() {
    protoResult = AnalysisProtosV2.CqueryResult.newBuilder();
  }

  @Override
  public void close(boolean failFast) throws IOException {
    if (!failFast && printStream != null) {
      if (options.protoIncludeConfigurations) {
        writeData(protoResult.build());
      } else {
        // Documentation promises that setting this flag to false means we convert directly
        // to the build.proto format. This is hard to test in integration testing due to the way
        // proto output is turned readable (codex). So change the following code with caution.
        QueryResult.Builder queryResult = Build.QueryResult.newBuilder();
        protoResult.getResultsList().forEach(ct -> queryResult.addTarget(ct.getTarget()));
        writeData(queryResult.build());
      }
      printStream.flush();
    }
  }

  private void writeData(Message message) throws IOException {
    switch (outputType) {
      case BINARY:
        message.writeTo(outputStream);
        break;
      case TEXT:
        TextFormat.print(message, printStream);
        break;
      case JSON:
        jsonPrinter.appendTo(message, printStream);
        printStream.append('\n');
        break;
      default:
        throw new IllegalStateException("Unknown outputType " + outputType.formatName());
    }
  }

  @Override
  public String getName() {
    return outputType.formatName();
  }

  @VisibleForTesting
  public AnalysisProtosV2.CqueryResult getProtoResult() {
    return protoResult.build();
  }

  @Override
  public void processOutput(Iterable<KeyedConfiguredTarget> partialResult)
      throws InterruptedException {
    ConfiguredProtoOutputFormatter formatter = new ConfiguredProtoOutputFormatter();
    formatter.setOptions(options, resolver, skyframeExecutor.getDigestFunction().getHashFunction());
    for (KeyedConfiguredTarget keyedConfiguredTarget : partialResult) {
      AnalysisProtosV2.ConfiguredTarget.Builder builder =
          AnalysisProtosV2.ConfiguredTarget.newBuilder();

      // Re: testing. Since this formatter relies on the heavily tested ProtoOutputFormatter class
      // for all its work with targets, ProtoOuputFormatterCallbackTest doesn't test any of the
      // logic in this next line. If this were to change (i.e. we manipulate targets any further),
      // we will want to add relevant tests.
      currentTarget = keyedConfiguredTarget;
      builder.setTarget(formatter.toTargetProtoBuffer(accessor.getTarget(keyedConfiguredTarget)));

      if (options.protoIncludeConfigurations) {
        String checksum = keyedConfiguredTarget.getConfigurationChecksum();
        builder.setConfiguration(
            AnalysisProtosV2.Configuration.newBuilder().setChecksum(String.valueOf(checksum)));
      }

      protoResult.addResults(builder.build());
    }
  }

  private class ConfiguredProtoOutputFormatter extends ProtoOutputFormatter {
    @Override
    protected void addAttributes(
        Build.Rule.Builder rulePb, Rule rule, Object extraDataForAttrHash) {
      // We know <code>currentTarget</code> will be either an AliasConfiguredTarget or
      // RuleConfiguredTarget,
      // because this method is only triggered in ProtoOutputFormatter.toTargetProtoBuffer when
      // the target in currentTarget is an instanceof Rule.
      ImmutableMap<Label, ConfigMatchingProvider> configConditions =
          currentTarget.getConfiguredTarget().getConfigConditions();
      ConfiguredAttributeMapper attributeMapper =
          ConfiguredAttributeMapper.of(
              rule,
              configConditions,
              currentTarget.getConfigurationChecksum(),
              /*alwaysSucceed=*/ false);
      for (Attribute attr : sortAttributes(rule.getAttributes())) {
        if (!shouldIncludeAttribute(rule, attr)) {
          continue;
        }
        Object attributeValue = attributeMapper.get(attr.getName(), attr.getType());
        Build.Attribute serializedAttribute =
            AttributeFormatter.getAttributeProto(
                attr,
                attributeValue,
                rule.isAttributeValueExplicitlySpecified(attr),
                /*encodeBooleanAndTriStateAsIntegerAndString=*/ true);
        rulePb.addAttribute(serializedAttribute);
      }
    }
  }

  static List<Attribute> sortAttributes(Iterable<Attribute> attributes) {
    return Ordering.from(Comparator.comparing(Attribute::getName)).sortedCopy(attributes);
  }
}
