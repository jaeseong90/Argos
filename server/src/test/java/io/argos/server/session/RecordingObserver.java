/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.ArrayList;
import java.util.List;

/** A server-side stream observer that records what the server sends (unit tests). */
final class RecordingObserver<T> extends ServerCallStreamObserver<T> {

  final List<T> messages = new ArrayList<>();
  Status status;
  Throwable error;
  boolean completed;
  boolean cancelled;
  boolean ready = true;
  Runnable onCancel;

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setOnCancelHandler(Runnable handler) {
    this.onCancel = handler;
  }

  @Override
  public void setCompression(String compression) {}

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public void setOnReadyHandler(Runnable handler) {}

  @Override
  public void disableAutoInboundFlowControl() {}

  @Override
  public void request(int count) {}

  @Override
  public void setMessageCompression(boolean enable) {}

  @Override
  public synchronized void onNext(T value) {
    if (status != null) {
      throw new IllegalStateException("call closed");
    }
    messages.add(value);
  }

  @Override
  public synchronized void onError(Throwable t) {
    error = t;
    status = Status.fromThrowable(t);
  }

  @Override
  public synchronized void onCompleted() {
    completed = true;
    status = Status.OK;
  }

  /** Simulates the transport cancelling the call. */
  void cancel() {
    cancelled = true;
    if (onCancel != null) {
      onCancel.run();
    }
  }

  synchronized List<T> messages() {
    return new ArrayList<>(messages);
  }
}
