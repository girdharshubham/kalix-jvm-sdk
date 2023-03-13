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

package kalix.javasdk.impl

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

import com.google.api.AnnotationsProto
import com.google.api.CustomHttpPattern
import com.google.api.HttpRule
import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.MethodOptions
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.Empty
import com.google.protobuf.{ Any => JavaPbAny }
import kalix.javasdk.impl.AnySupport.ProtobufEmptyTypeUrl
import kalix.javasdk.impl.reflection.AnyJsonRequestServiceMethod
import kalix.javasdk.impl.reflection.CombinedSubscriptionServiceMethod
import kalix.javasdk.impl.reflection.DeleteServiceMethod
import kalix.javasdk.impl.reflection.DynamicMessageContext
import kalix.javasdk.impl.reflection.ExtractorCreator
import kalix.javasdk.impl.reflection.KalixMethod
import kalix.javasdk.impl.reflection.NameGenerator
import kalix.javasdk.impl.reflection.ParameterExtractor
import kalix.javasdk.impl.reflection.ParameterExtractors
import kalix.javasdk.impl.reflection.ParameterExtractors.HeaderExtractor
import kalix.javasdk.impl.reflection.RestServiceIntrospector
import kalix.javasdk.impl.reflection.RestServiceIntrospector.BodyParameter
import kalix.javasdk.impl.reflection.RestServiceIntrospector.HeaderParameter
import kalix.javasdk.impl.reflection.RestServiceIntrospector.PathParameter
import kalix.javasdk.impl.reflection.RestServiceIntrospector.QueryParamParameter
import kalix.javasdk.impl.reflection.RestServiceIntrospector.UnhandledParameter
import kalix.javasdk.impl.reflection.ServiceMethod
import kalix.javasdk.impl.reflection.SubscriptionServiceMethod
import kalix.javasdk.impl.reflection.SyntheticRequestServiceMethod
// TODO: abstract away spring dependency
import org.springframework.core.MethodParameter
import org.springframework.web.bind.annotation.RequestMethod

/**
 * The component descriptor is both used for generating the protobuf service descriptor to communicate the service type
 * and methods etc. to Kalix and for the reflective routers routing incoming calls to the right method of the user
 * component class.
 */
private[kalix] object ComponentDescriptor {

  def descriptorFor(component: Class[_], messageCodec: JsonMessageCodec): ComponentDescriptor =
    ComponentDescriptorFactory.getFactoryFor(component).buildDescriptorFor(component, messageCodec, new NameGenerator)

  def apply(
      nameGenerator: NameGenerator,
      messageCodec: JsonMessageCodec,
      serviceName: String,
      serviceOptions: Option[kalix.ServiceOptions],
      packageName: String,
      kalixMethods: Seq[KalixMethod],
      additionalMessages: Seq[ProtoMessageDescriptors] = Nil): ComponentDescriptor = {

    val otherMessageProtos: Seq[DescriptorProtos.DescriptorProto] =
      additionalMessages.flatMap(pm => pm.mainMessageDescriptor +: pm.additionalMessageDescriptors)

    val grpcService = ServiceDescriptorProto.newBuilder()
    grpcService.setName(serviceName)

    serviceOptions.foreach { serviceOpts =>
      val options =
        DescriptorProtos.ServiceOptions
          .newBuilder()
          .setExtension(kalix.Annotations.service, serviceOpts)
          .build()
      grpcService.setOptions(options)
    }

    def methodToNamedComponentMethod(kalixMethod: KalixMethod): NamedComponentMethod = {

      kalixMethod.validate()

      val (inputMessageName: String, extractors: Map[Int, ExtractorCreator], inputProto: Option[DescriptorProto]) =
        kalixMethod.serviceMethod match {
          case serviceMethod: SyntheticRequestServiceMethod =>
            val (inputProto, extractors) =
              buildSyntheticMessageAndExtractors(nameGenerator, serviceMethod, kalixMethod.entityKeys)
            (inputProto.getName, extractors, Some(inputProto))

          case _: AnyJsonRequestServiceMethod =>
            (JavaPbAny.getDescriptor.getFullName, Map.empty[Int, ExtractorCreator], None)

          case _: DeleteServiceMethod =>
            (Empty.getDescriptor.getFullName, Map.empty[Int, ExtractorCreator], None)
        }

      val grpcMethodName = nameGenerator.getName(kalixMethod.serviceMethod.methodName.capitalize)
      val grpcMethodBuilder =
        buildGrpcMethod(
          grpcMethodName,
          inputMessageName,
          kalixMethod.serviceMethod.streamIn,
          kalixMethod.serviceMethod.streamOut)

      grpcMethodBuilder.setOptions(createMethodOptions(kalixMethod))

      val grpcMethod = grpcMethodBuilder.build()
      grpcService.addMethod(grpcMethod)

      NamedComponentMethod(
        kalixMethod.serviceMethod,
        messageCodec,
        grpcMethodName,
        extractors,
        inputMessageName,
        inputProto)
    }

    val namedMethods: Seq[NamedComponentMethod] = kalixMethods.map(methodToNamedComponentMethod)
    val inputMessageProtos: Set[DescriptorProtos.DescriptorProto] = namedMethods.flatMap(_.inputProto).toSet

    val fileDescriptor: Descriptors.FileDescriptor =
      ProtoDescriptorGenerator.genFileDescriptor(
        serviceName,
        packageName,
        grpcService.build(),
        inputMessageProtos ++ otherMessageProtos)

    val methods: Map[String, CommandHandler] =
      namedMethods.map { method => (method.grpcMethodName, method.toCommandHandler(fileDescriptor)) }.toMap

    val serviceDescriptor: Descriptors.ServiceDescriptor =
      fileDescriptor.findServiceByName(grpcService.getName)

    new ComponentDescriptor(serviceName, packageName, methods, serviceDescriptor, fileDescriptor)
  }

  private def createMethodOptions(kalixMethod: KalixMethod): MethodOptions = {

    val methodOptions = MethodOptions.newBuilder()

    kalixMethod.serviceMethod match {
      case syntheticRequestServiceMethod: SyntheticRequestServiceMethod =>
        val httpRuleBuilder = buildHttpRule(syntheticRequestServiceMethod)
        syntheticRequestServiceMethod.params.collectFirst { case BodyParameter(_, _) =>
          httpRuleBuilder.setBody("json_body")
        }
        methodOptions.setExtension(AnnotationsProto.http, httpRuleBuilder.build())
      case _ => //ignore
    }

    kalixMethod.methodOptions.foreach(option => methodOptions.setExtension(kalix.Annotations.method, option))
    methodOptions.build()
  }

  // intermediate format that references input message by name
  // once we have built the full file descriptor, we can look up for the input message using its name
  private case class NamedComponentMethod(
      serviceMethod: ServiceMethod,
      messageCodec: JsonMessageCodec,
      grpcMethodName: String,
      extractorCreators: Map[Int, ExtractorCreator],
      inputMessageName: String,
      inputProto: Option[DescriptorProto]) {

    type ParameterExtractorsArray = Array[ParameterExtractor[InvocationContext, AnyRef]]

    def toCommandHandler(fileDescriptor: FileDescriptor): CommandHandler = {
      serviceMethod match {
        case method: SyntheticRequestServiceMethod =>
          val syntheticMessageDescriptor = fileDescriptor.findMessageTypeByName(inputMessageName)
          if (syntheticMessageDescriptor == null)
            throw new RuntimeException(
              "Unknown message type [" + inputMessageName + "], known are [" + fileDescriptor.getMessageTypes.asScala
                .map(_.getName) + "]")

          val parameterExtractors: ParameterExtractorsArray =
            if (method.callable) {
              method.params.zipWithIndex.map { case (param, idx) =>
                extractorCreators.find(_._1 == idx) match {
                  case Some((_, creator)) => creator(syntheticMessageDescriptor)
                  case None               =>
                    // Yet to resolve this parameter, resolve now
                    param match {
                      case hp: HeaderParameter =>
                        new HeaderExtractor[AnyRef](hp.name, identity)
                      case UnhandledParameter(p) =>
                        throw new RuntimeException(
                          s"Unhandled parameter for [${serviceMethod.methodName}]: [$p], message type: " + inputMessageName)
                      // FIXME not handled: BodyParameter(_, _), PathParameter(_, _), QueryParamParameter(_, _)
                    }
                }
              }.toArray
            } else Array.empty

          // synthetic request always have proto messages as input,
          // their type url will are prefixed by DefaultTypeUrlPrefix
          // It's possible for a user to configure another prefix, but this is done through the Kalix instance
          // and the Java SDK doesn't expose it.
          val typeUrl = AnySupport.DefaultTypeUrlPrefix + "/" + syntheticMessageDescriptor.getFullName

          CommandHandler(
            grpcMethodName,
            messageCodec,
            syntheticMessageDescriptor,
            Map(typeUrl -> MethodInvoker(method.javaMethod, parameterExtractors)))

        case method: CombinedSubscriptionServiceMethod =>
          val methodInvokers =
            method.methodsMap.map { case (typeUrl, meth) =>
              val parameterExtractors: ParameterExtractorsArray =
                meth.getParameterTypes.map(param => ParameterExtractors.AnyBodyExtractor[AnyRef](param))

              (typeUrl, MethodInvoker(meth, parameterExtractors))
            }

          CommandHandler(grpcMethodName, messageCodec, JavaPbAny.getDescriptor, methodInvokers)

        case method: SubscriptionServiceMethod =>
          val methodInvokers =
            serviceMethod.javaMethodOpt.map { meth =>

              val parameterExtractors: ParameterExtractorsArray =
                Array(ParameterExtractors.AnyBodyExtractor(method.inputType))

              val typeUrl = messageCodec.typeUrlFor(method.inputType)
              (typeUrl, MethodInvoker(meth, parameterExtractors))
            }.toMap

          CommandHandler(grpcMethodName, messageCodec, JavaPbAny.getDescriptor, methodInvokers)

        case method: AnyJsonRequestServiceMethod =>
          val methodInvokers = serviceMethod.javaMethodOpt.map { meth =>

            val parameterExtractors: ParameterExtractorsArray =
              Array(ParameterExtractors.AnyBodyExtractor(method.inputType))

            val typeUrl = messageCodec.typeUrlFor(method.inputType)
            (typeUrl, MethodInvoker(meth, parameterExtractors))
          }.toMap

          CommandHandler(grpcMethodName, messageCodec, JavaPbAny.getDescriptor, methodInvokers)

        case _: DeleteServiceMethod =>
          val methodInvokers = serviceMethod.javaMethodOpt.map { meth =>
            (ProtobufEmptyTypeUrl, MethodInvoker(meth, Array.empty[ParameterExtractor[InvocationContext, AnyRef]]))
          }.toMap

          CommandHandler(grpcMethodName, messageCodec, Empty.getDescriptor, methodInvokers)
      }

    }
  }

  private def buildSyntheticMessageAndExtractors(
      nameGenerator: NameGenerator,
      serviceMethod: SyntheticRequestServiceMethod,
      entityKeys: Seq[String] = Seq.empty): (DescriptorProto, Map[Int, ExtractorCreator]) = {

    val inputMessageName = nameGenerator.getName(serviceMethod.methodName.capitalize + "KalixSyntheticRequest")

    val inputMessageDescriptor = DescriptorProto.newBuilder()
    inputMessageDescriptor.setName(inputMessageName)

    val indexedParams = serviceMethod.params.zipWithIndex

    val bodyFieldDescs = bodyFieldDescriptors(indexedParams)
    val pathParamOffset = 2 //1 is reserved for json_body
    val pathParamFieldDescs = pathParamFieldDescriptors(serviceMethod, indexedParams, entityKeys, pathParamOffset)
    val queryFieldsOffset = pathParamFieldDescs.size + pathParamOffset
    val queryFieldDescs = queryParamFieldDescriptors(indexedParams, queryFieldsOffset, entityKeys)

    inputMessageDescriptor.addAllField((bodyFieldDescs ++ pathParamFieldDescs ++ queryFieldDescs).asJava)

    val bodyField: Option[(Int, ExtractorCreator)] = bodyFieldExtractors(indexedParams)
    val pathParamFieldsExtractors = pathParamExtractors(indexedParams, pathParamFieldDescs)
    val queryFieldExtractors = queryParamExtractors(indexedParams, queryFieldDescs)

    val inputProto = inputMessageDescriptor.build()
    val extractors = (bodyField ++ pathParamFieldsExtractors ++ queryFieldExtractors).toMap
    (inputProto, extractors)
  }

  private def queryParamExtractors(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)],
      queryFieldDescs: Seq[FieldDescriptorProto]): Seq[(Int, ExtractorCreator)] = {
    indexedParams
      .collect { case (qp: QueryParamParameter, idx) =>
        idx -> qp
      }
      .map { case (paramIdx, param) =>
        paramIdx -> toExtractor(param.param, queryFieldDescs)
      }
  }

  private def toExtractor(methodParameter: MethodParameter, queryFieldDescs: Seq[FieldDescriptorProto]) = {
    val typeName = methodParameter.getGenericParameterType.getTypeName
    if (typeName == "short") {
      new ExtractorCreator {
        override def apply(descriptor: Descriptors.Descriptor): ParameterExtractor[DynamicMessageContext, AnyRef] = {
          new ParameterExtractors.FieldExtractor[java.lang.Short](
            descriptor.findFieldByNumber(fieldNumber(methodParameter.getParameterName, queryFieldDescs)),
            _.asInstanceOf[java.lang.Integer].toShort)
        }
      }
    } else if (typeName == "byte") {
      new ExtractorCreator {
        override def apply(descriptor: Descriptors.Descriptor): ParameterExtractor[DynamicMessageContext, AnyRef] = {
          new ParameterExtractors.FieldExtractor[java.lang.Byte](
            descriptor.findFieldByNumber(fieldNumber(methodParameter.getParameterName, queryFieldDescs)),
            _.asInstanceOf[java.lang.Integer].byteValue())
        }
      }
    } else if (typeName == "char") {
      new ExtractorCreator {
        override def apply(descriptor: Descriptors.Descriptor): ParameterExtractor[DynamicMessageContext, AnyRef] = {
          new ParameterExtractors.FieldExtractor[java.lang.Character](
            descriptor.findFieldByNumber(fieldNumber(methodParameter.getParameterName, queryFieldDescs)),
            Int.unbox(_).toChar)
        }
      }
    } else {
      new ExtractorCreator {
        override def apply(descriptor: Descriptors.Descriptor): ParameterExtractor[DynamicMessageContext, AnyRef] = {
          new ParameterExtractors.FieldExtractor[AnyRef](
            descriptor.findFieldByNumber(fieldNumber(methodParameter.getParameterName, queryFieldDescs)),
            identity)
        }
      }
    }
  }

  private def queryParamFieldDescriptors(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)],
      queryFieldsOffset: Int,
      entityKeys: Seq[String]): Seq[FieldDescriptorProto] = {
    indexedParams
      .collect { case (qp: QueryParamParameter, idx) =>
        idx -> qp
      }
      .sortBy(_._2.name)
      .zipWithIndex
      .map { case ((_, param), fieldIdx) =>
        val fieldNumber = fieldIdx + queryFieldsOffset
        FieldDescriptorProto
          .newBuilder()
          .setName(param.name)
          .setNumber(fieldNumber)
          .setType(mapJavaTypeToProtobuf(param.param.getGenericParameterType))
          .setLabel(
            mapJavaWrapperToLabel(param.param.getGenericParameterType, isRequired = param.annotation.required()))
          .setOptions(addEntityKeyIfNeeded(entityKeys, param.name))
          .build()
      }
  }

  private def pathParamExtractors(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)],
      pathParamFieldDescs: Seq[FieldDescriptorProto]): Seq[(Int, ExtractorCreator)] = {
    indexedParams
      .collect { case (p: PathParameter, idx) =>
        idx -> p
      }
      .map { case (idx, p) =>
        idx -> toExtractor(p.param, pathParamFieldDescs)
      }
  }

  private def pathParamFieldDescriptors(
      serviceMethod: SyntheticRequestServiceMethod,
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)],
      entityKeys: Seq[String],
      pathParamOffset: Int): Seq[FieldDescriptorProto] = {
    serviceMethod.parsedPath.fields.zipWithIndex.map { case (paramName, fieldIdx) =>
      val fieldNumber = fieldIdx + pathParamOffset
      val (paramType, isRequired) = paramDetails(indexedParams, paramName)
      FieldDescriptorProto
        .newBuilder()
        .setName(paramName)
        .setNumber(fieldNumber)
        .setType(mapJavaTypeToProtobuf(paramType))
        .setLabel(mapJavaWrapperToLabel(paramType, isRequired))
        .setOptions(addEntityKeyIfNeeded(entityKeys, paramName))
        .build()
    }
  }

  private def addEntityKeyIfNeeded(entityKeys: Seq[String], paramName: String): DescriptorProtos.FieldOptions =
    if (entityKeys.contains(paramName)) {
      val fieldOptions = kalix.FieldOptions.newBuilder().setEntityKey(true).build()
      DescriptorProtos.FieldOptions
        .newBuilder()
        .setExtension(kalix.Annotations.field, fieldOptions)
        .build()
    } else {
      DescriptorProtos.FieldOptions.getDefaultInstance
    }

  private def paramDetails(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)],
      paramName: String): (Type, Boolean) = {
    indexedParams
      .collectFirst {
        case (p: PathParameter, _) if p.name == paramName =>
          (p.param.getGenericParameterType, p.annotation.required())
      }
      .getOrElse((classOf[String], true))
  }

  private def fieldNumber(fieldName: String, pathParamFieldsDesc: Seq[FieldDescriptorProto]): Int = {
    pathParamFieldsDesc
      .find(_.getName == fieldName)
      .map(_.getNumber)
      .getOrElse(throw new IllegalStateException(s"Missing field descriptor for field name: $fieldName"))
  }

  private def bodyFieldDescriptors(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)]): Seq[FieldDescriptorProto] = {
    indexedParams.collectFirst { case (BodyParameter(_, _), _) =>
      FieldDescriptorProto
        .newBuilder()
        // todo ensure this is unique among field names
        .setName("json_body")
        // Always put the body at position 1 - even if there's no body, leave position 1 free. This keeps the body
        // parameter stable in case the user adds a body.
        .setNumber(1)
        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
        .setTypeName("google.protobuf.Any")
        .build()
    }.toSeq
  }

  private def bodyFieldExtractors(
      indexedParams: Seq[(RestServiceIntrospector.RestMethodParameter, Int)]): Option[(Int, ExtractorCreator)] = {
    indexedParams.collectFirst { case (BodyParameter(param, _), idx) =>
      idx -> new ExtractorCreator {
        override def apply(descriptor: Descriptors.Descriptor): ParameterExtractor[DynamicMessageContext, AnyRef] = {
          new ParameterExtractors.BodyExtractor(descriptor.findFieldByNumber(1), param.getParameterType)
        }
      }
    }
  }

  @tailrec
  private def mapJavaTypeToProtobuf(javaType: Type): FieldDescriptorProto.Type = {
    // todo make this smarter, eg, customizable parameter deserializers, UUIDs, byte arrays, enums etc
    if (javaType == classOf[String]) {
      FieldDescriptorProto.Type.TYPE_STRING
    } else if (javaType == classOf[java.lang.Long] || javaType.getTypeName == "long") {
      FieldDescriptorProto.Type.TYPE_INT64
    } else if (javaType == classOf[java.lang.Integer] || javaType.getTypeName == "int"
      || javaType.getTypeName == "short"
      || javaType.getTypeName == "byte"
      || javaType.getTypeName == "char") {
      FieldDescriptorProto.Type.TYPE_INT32
    } else if (javaType == classOf[java.lang.Double] || javaType.getTypeName == "double") {
      FieldDescriptorProto.Type.TYPE_DOUBLE
    } else if (javaType == classOf[java.lang.Float] || javaType.getTypeName == "float") {
      FieldDescriptorProto.Type.TYPE_FLOAT
    } else if (javaType == classOf[java.lang.Boolean] || javaType.getTypeName == "boolean") {
      FieldDescriptorProto.Type.TYPE_BOOL
    } else if (javaType == classOf[ByteString]) {
      FieldDescriptorProto.Type.TYPE_BYTES
    } else if (isCollection(javaType)) {
      mapJavaTypeToProtobuf(javaType.asInstanceOf[ParameterizedType].getActualTypeArguments.head)
    } else {
      throw new RuntimeException("Don't know how to extract type " + javaType + " from path.")
    }
  }

  private def mapJavaWrapperToLabel(javaType: Type, isRequired: Boolean): FieldDescriptorProto.Label =
    if (isCollection(javaType))
      FieldDescriptorProto.Label.LABEL_REPEATED
    else if (isRequired)
      FieldDescriptorProto.Label.LABEL_REQUIRED
    else
      FieldDescriptorProto.Label.LABEL_OPTIONAL

  private def isCollection(javaType: Type): Boolean = javaType.isInstanceOf[ParameterizedType] &&
    classOf[util.Collection[_]]
      .isAssignableFrom(javaType.asInstanceOf[ParameterizedType].getRawType.asInstanceOf[Class[_]])

  private def buildHttpRule(serviceMethod: SyntheticRequestServiceMethod) = {
    val httpRule = HttpRule.newBuilder()
    val pathTemplate = serviceMethod.pathTemplate
    serviceMethod.requestMethod match {
      case RequestMethod.GET =>
        httpRule.setGet(pathTemplate)
      case RequestMethod.POST =>
        httpRule.setPost(pathTemplate)
      case RequestMethod.PUT =>
        httpRule.setPut(pathTemplate)
      case RequestMethod.PATCH =>
        httpRule.setPatch(pathTemplate)
      case RequestMethod.DELETE =>
        httpRule.setDelete(pathTemplate)
      case other =>
        httpRule.setCustom(
          CustomHttpPattern
            .newBuilder()
            .setKind(other.name())
            .setPath(pathTemplate))
    }
    httpRule
  }

  private def buildGrpcMethod(
      grpcMethodName: String,
      inputTypeName: String,
      streamIn: Boolean,
      streamOut: Boolean): MethodDescriptorProto.Builder =
    MethodDescriptorProto
      .newBuilder()
      .setName(grpcMethodName)
      .setInputType(inputTypeName)
      .setClientStreaming(streamIn)
      .setServerStreaming(streamOut)
      .setOutputType("google.protobuf.Any")

}

private[kalix] final case class ComponentDescriptor private (
    serviceName: String,
    packageName: String,
    commandHandlers: Map[String, CommandHandler],
    serviceDescriptor: Descriptors.ServiceDescriptor,
    fileDescriptor: Descriptors.FileDescriptor)