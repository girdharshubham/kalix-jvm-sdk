/* This code was generated by Akka Serverless tooling.
 * As long as this file exists it will not be re-generated.
 * You are free to make changes to this file.
 */

package com.example.actions;

import com.akkaserverless.javasdk.DeferredCall;
import com.akkaserverless.javasdk.SideEffect;
import com.akkaserverless.javasdk.action.ActionCreationContext;
import com.example.Components;
import com.example.ComponentsImpl;
import com.example.CounterApi;
import com.google.protobuf.Empty;

import java.util.concurrent.CompletionStage;

// tag::controller-forward[]
// tag::controller-side-effect[]
/**
 * An action.
 */
public class DoubleCounterAction extends AbstractDoubleCounterAction {

  public DoubleCounterAction(ActionCreationContext creationContext) {
  }

// end::controller-side-effect[]
// tag::controller-side-effect[]
  
  // Handler for "Increase" not shown in this snippet

// end::controller-side-effect[]

  /**
   * Handler for "Increase".
   */
  @Override
  public Effect<Empty> increase(CounterApi.IncreaseValue increaseValue) {
    int doubled = increaseValue.getValue() * 2;
    CounterApi.IncreaseValue increaseValueDoubled =
        increaseValue.toBuilder().setValue(doubled).build(); // <1>

    return effects()
            .forward(components().counter().increase(increaseValueDoubled)); // <2>
  }

  // end::controller-forward[]
  // tag::controller-side-effect[]
  /**
   * Handler for "IncreaseWithSideEffect".
   */
  @Override 
  public Effect<Empty> increaseWithSideEffect(CounterApi.IncreaseValue increaseValue) {
    int doubled = increaseValue.getValue() * 2;
    CounterApi.IncreaseValue increaseValueDoubled =
        increaseValue.toBuilder().setValue(doubled).build(); // <1>

    return effects()
            .reply(Empty.getDefaultInstance()) // <2>
            .addSideEffect( // <3>
                SideEffect.of(components().counter().increase(increaseValueDoubled)));
  }
  // end::controller-side-effect[]

  // almost like forward, but allows for transforming response
  public Effect<Empty> forwardWithGrpcApi(CounterApi.IncreaseValue increaseValue) {
    int doubled = increaseValue.getValue() * 2;
    CounterApi.IncreaseValue increaseValueDoubled =
        increaseValue.toBuilder().setValue(doubled).build();

    CompletionStage<Empty> transformedResponse = components().counter().increase(increaseValueDoubled).execute()
        .thenApply(empty -> {
          // ridiculous but for now transforming by discarding and returning another empty will do
          return Empty.getDefaultInstance();
        });
    return effects().asyncReply(transformedResponse);
  }

  // regular async sequence of operations
  public Effect<CounterApi.CurrentCounter> sequentialComposition(CounterApi.IncreaseValue increaseValue) {
    int doubled = increaseValue.getValue() * 2;
    CounterApi.IncreaseValue increaseValueDoubled =
        increaseValue.toBuilder().setValue(doubled).build();
    CompletionStage<CounterApi.CurrentCounter> increaseAndValueAfter =
        components().counter().increase(increaseValueDoubled)
        .execute()
        .thenCompose(empty ->
            // important for docs to describe that the entity might change between the two commands
            components().counter().getCurrentCounter(
                CounterApi.GetCounter.newBuilder().setCounterId(increaseValue.getCounterId()).build())
                .execute()
        );
    return effects().asyncReply(increaseAndValueAfter);
  }

  // Maybe this is not something we should show in docs, happens in parallel (right now)
  // but could be good for something to describe that there is no consistency/transaction here
  public Effect<CounterApi.CurrentCounter> sumOfMy3FavouriteCounterValues(Empty empty) {
    CompletionStage<CounterApi.CurrentCounter> counter1 =
        components().counter().getCurrentCounter(CounterApi.GetCounter.newBuilder().setCounterId("counter-1").build())
            .execute();
    CompletionStage<CounterApi.CurrentCounter> counter2 =
        components().counter().getCurrentCounter(CounterApi.GetCounter.newBuilder().setCounterId("counter-2").build())
            .execute();
    CompletionStage<CounterApi.CurrentCounter> counter3 =
        components().counter().getCurrentCounter(CounterApi.GetCounter.newBuilder().setCounterId("counter-3").build())
            .execute();

    CompletionStage<CounterApi.CurrentCounter> sumOfAllThree = counter1.thenCombine(counter2, (currentCounter1, currentCounter2) ->
        currentCounter1.getValue() + currentCounter2.getValue()
    ).thenCombine(counter3, (sumOf1And2, currentCounter3) ->
        CounterApi.CurrentCounter.newBuilder().setValue(sumOf1And2 + currentCounter3.getValue()).build()
    );

    return effects().asyncReply(sumOfAllThree);
  }
  // tag::controller-side-effect[]
  // tag::controller-forward[]
}
// end::controller-forward[]
// end::controller-side-effect[]