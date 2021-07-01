/*
 * Copyright 2021 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package customer;

import com.akkaserverless.javasdk.Reply;
import com.akkaserverless.javasdk.impl.*;
import com.akkaserverless.javasdk.impl.valueentity.AdaptedCommandContext;
import com.akkaserverless.javasdk.lowlevel.ValueEntityHandler;
import com.akkaserverless.javasdk.valueentity.CommandContext;
import com.akkaserverless.javasdk.valueentity.ValueEntityBase;
import com.google.protobuf.Any;
import com.google.protobuf.GeneratedMessageV3;
import customer.api.CustomerApi;
import customer.domain.CustomerDomain;
import scala.None$;
import scala.Option;
import scalapb.UnknownFieldSet;

import java.lang.reflect.InvocationTargetException;

/** This class would be generated by codegen */
public class CustomerValueEntityHandler implements ValueEntityHandler {
  final CustomerValueEntity entity;
  final AnySupport anySupport;

  CustomerValueEntityHandler(CustomerValueEntity entity, AnySupport anySupport) {
    this.entity = entity;
    this.anySupport = anySupport;
  }

  @Override
  public ValueEntityBase.Effect<Any> handleCommand(Any command, Any state, CommandContext<Any> context)
      throws Throwable {
    AdaptedCommandContext<CustomerDomain.CustomerState> adaptedContext =
        new AdaptedCommandContext(context, anySupport);
    try {
      CustomerDomain.CustomerState parsedState =
          CustomerDomain.CustomerState.parseFrom(state.getValue());
      // TODO we used to support passing in the command as Jackson-parsed model object
      // or as ScalaPB class as well. With this change we tie ourselves to Java protobuf.
      ValueEntityBase.Effect<? extends GeneratedMessageV3> effect = invoke(command, parsedState, adaptedContext);
      // TODO we used to support accepting ScalaPB and Jackson objects as responses as well.
      //  Are we OK with losing that? I guess we could have separate methods for that on the
      //  builder, though it's not obvious how to achieve type-safety for those then.

      // FIXME probably ValueEntityHandler should be adapted to expect an `Effect<? extends
      // GenerateMessageV3>`?
      return null;
    } catch (Exception e) {
      Throwable unwrapped;
      if (e.getClass().isAssignableFrom(InvocationTargetException.class) && e.getCause() != null) {
        unwrapped = e.getCause();
      } else {
        unwrapped = e;
      }
      if (unwrapped.getClass().isAssignableFrom(FailInvoked$.class)) {
        throw unwrapped;
      } else {
        throw unwrapped.getCause();
      }
    }
  }

  @Override
  public com.google.protobuf.any.Any emptyState() {
    return com.google.protobuf.any.Any.apply(
        AnySupport.DefaultTypeUrlPrefix()
            + "/"
            + CustomerDomain.CustomerState.getDescriptor().getFullName(),
        entity.emptyState().toByteString(),
        UnknownFieldSet.empty());
  }

  public ValueEntityBase.Effect<? extends GeneratedMessageV3> invoke(
      Any command,
      CustomerDomain.CustomerState state,
      CommandContext<CustomerDomain.CustomerState> context)
      throws Throwable {
    switch (context.commandName()) {
      case "Create":
        return entity.create(
            // TODO we used to support any 'Jsonable' class here as well, parsing them with Jackson,
            // and also ScalaPB classes.
            // are we OK with restriction ourselves to actual Java protobuf classes?
            CustomerApi.Customer.parseFrom(command.getValue()), state, context);
      case "ChangeName":
        return entity.changeName(
            CustomerApi.ChangeNameRequest.parseFrom(command.getValue()), state, context);
      case "GetCustomer":
        return entity.getCustomer(
            CustomerApi.GetCustomerRequest.parseFrom(command.getValue()), state, context);
      default:
        Option<?> noneOption = None$.MODULE$;
        throw new EntityExceptions.EntityException(
            context.entityId(),
            context.commandId(),
            context.commandName(),
            "No command handler found for command ["
                + context.commandName()
                + "] on "
                + entity.getClass().toString(),
            (Option<Throwable>) noneOption);
    }
  }
}
