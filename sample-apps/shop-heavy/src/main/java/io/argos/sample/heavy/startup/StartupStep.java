/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.startup;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * One library initialization that shop-heavy performs while the Spring context refreshes, before
 * the web server binds its port: what a large enterprise app does at startup (build SDK clients,
 * compile templates, prepare report and mail templates, generate the API description, register
 * serializers). Every step does real work with its library and returns a small summary; a failing
 * step stops the app, so a started app is also a working one.
 */
public interface StartupStep {

  /** Short stable name, e.g. {@code aws-s3}. */
  String name();

  /** The libraries the step initializes, e.g. {@code AWS SDK 2 (S3 client, presigner)}. */
  String libraries();

  /** Runs the step: offline, no network call, bounded work. */
  Map<String, Object> run() throws Exception;

  /** A step from a name, a library description and an action. */
  static StartupStep of(String name, String libraries, Callable<Map<String, Object>> action) {
    return new StartupStep() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String libraries() {
        return libraries;
      }

      @Override
      public Map<String, Object> run() throws Exception {
        return action.call();
      }
    };
  }
}
