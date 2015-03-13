package builder.api_json

import builder.ServiceValidator
import core.{ClientFetcher, Importer, ServiceConfiguration, ServiceFetcher, Util}
import lib.{Datatype, Methods, Primitives, Text, Type, Kind, UrlKey}
import com.gilt.apidoc.spec.v0.models.{Enum, Field, Method, Service}
import play.api.libs.json.{JsObject, Json, JsValue}
import com.fasterxml.jackson.core.{ JsonParseException, JsonProcessingException }
import com.fasterxml.jackson.databind.JsonMappingException
import scala.util.{Failure, Success, Try}

case class ApiJsonServiceValidator(
  config: ServiceConfiguration,
  apiJson: String,
  fetcher: ServiceFetcher = new ClientFetcher()
) extends ServiceValidator {

  private val RequiredFields = Seq("name")

  lazy val service: Service = ServiceBuilder(config, internalService.get)

  def validate(): Either[Seq[String], Service] = {
    if (isValid) {
      Right(service)
    } else {
      Left(errors)
    }
  }

  private var parseError: Option[String] = None

  lazy val serviceForm: Option[JsObject] = {
    Try(Json.parse(apiJson)) match {
      case Success(v) => {
        v.asOpt[JsObject] match {
          case Some(o) => {
            Some(o)
          }
          case None => {
            parseError = Some("Must upload a Json Object")
            None
          }
        }
      }
      case Failure(ex) => ex match {
        case e: JsonParseException => {
          parseError = Some(e.getMessage)
          None
        }
        case e: JsonProcessingException => {
          parseError = Some(e.getMessage)
          None
        }
      }
    }
  }

  private lazy val internalService: Option[InternalServiceForm] = serviceForm.map(InternalServiceForm(_, fetcher))

  lazy val errors: Seq[String] = internalErrors match {
    case Nil => builder.ServiceSpecValidator(service).errors
    case e => e
  }

  private lazy val internalErrors: Seq[String] = {
    internalService match {

      case None => {
        if (apiJson.trim == "") {
          Seq("No Data")
        } else {
          Seq(parseError.getOrElse("Invalid JSON"))
        }
      }

      case Some(sd: InternalServiceForm) => {
        val requiredFieldErrors = validateRequiredFields()

        if (requiredFieldErrors.isEmpty) {
          validateKey ++
          validateImports ++
          validateEnums ++
          validateUnions ++
          validateHeaders ++
          validateFields ++
          validateOperations ++
          validateParameterBodies ++
          validateParameters ++
          validateResponses ++
          validatePathParameters ++
          validatePathParametersAreRequired

        } else {
          requiredFieldErrors
        }
      }
    }
  }

  private def validateKey(): Seq[String] = {
    internalService.get.key match {
      case None => Seq.empty
      case Some(key) => {
        val generated = UrlKey.generate(key)
        if (generated == key) {
          Seq.empty
        } else {
          Seq(s"Invalid url key. A valid key would be $generated")
        }
      }
    }
  }

  /**
   * Validate basic structure, returning a list of error messages
   */
  private def validateRequiredFields(): Seq[String] = {
    val missing = RequiredFields.filter { field =>
      (internalService.get.json \ field).asOpt[JsValue] match {
        case None => true
        case Some(_) => false
      }
    }
    if (missing.isEmpty) {
      Seq.empty
    } else {
      Seq("Missing: " + missing.mkString(", "))
    }
  }

  private def validateImports(): Seq[String] = {
    internalService.get.imports.flatMap { imp =>
      imp.uri match {
        case None => Seq("imports.uri is required")
        case Some(uri) => {
          Util.validateUri(uri) match {
            case Nil => Importer(fetcher, uri).validate  // TODO. need to cache somewhere to avoid a second lookup when parsing later
            case errors => errors
          }
        }
      }
    }
  }

  private def validateEnums(): Seq[String] = {
    internalService.get.enums.flatMap { enum =>
      enum.values.filter(_.name.isEmpty).map { value =>
        s"Enum[${enum.name}] - all values must have a name"
      }
    }
  }

  private def validateUnions(): Seq[String] = {
    internalService.get.unions.filter { !_.types.filter(_.datatype.isEmpty).isEmpty }.map { union =>
      s"Union[${union.name}] all types must have a name"
    }
  }

  private def validateHeaders(): Seq[String] = {
    val headersWithoutNames = internalService.get.headers.filter(_.name.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a name")
    }

    val headersWithoutTypes = internalService.get.headers.filter(_.datatype.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a type")
    }

    headersWithoutNames ++ headersWithoutTypes
  }

  private def validateFields(): Seq[String] = {
    val missingNames = internalService.get.models.flatMap { model =>
      model.fields.filter(_.name.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name}] must have a name"
      }
    }

    val missingTypes = internalService.get.models.flatMap { model =>
      model.fields.filter(!_.name.isEmpty).filter(_.datatype.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name.get}] must have a type"
      }
    }

    val warnings = internalService.get.models.flatMap { model =>
      model.fields.filter(f => !f.warnings.isEmpty && !f.name.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name.get}]: " + f.warnings.mkString(", ")
      }
    }

    missingTypes ++ missingNames ++ warnings
  }


  private def validateResponses(): Seq[String] = {
    val missingMethods = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.method match {
          case None => Some(opLabel(resource, op, "Missing HTTP method"))
          case Some(m) => None
        }
      }
    }

    val invalidCodes = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.flatMap { r =>
          Try(r.code.toInt) match {
            case Success(v) => None
            case Failure(ex) => ex match {
              case e: java.lang.NumberFormatException => {
                Some(opLabel(resource, op, s"Response code is not an integer[${r.code}]"))
              }
            }
          }
        }
      }
    }

    val warnings = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.filter(r => !r.warnings.isEmpty).map { r =>
          opLabel(resource, op, s"${r.code}: " + r.warnings.mkString(", "))
        }
      }
    }

    missingMethods ++ invalidCodes ++ warnings
  }

  private def validateParameterBodies(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.body.isEmpty).flatMap { op =>
        op.body.flatMap(_.datatype) match {
          case None => Some(opLabel(resource, op, "Body missing type"))
          case Some(_) => None
        }
      }
    }
  }

  private def opLabel(
    resource: InternalResourceForm,
    op: InternalOperationForm,
    message: String
  ): String = {
    val prefix = op.method match {
      case None => s"Resource[${resource.datatype.label}] ${op.path}"
      case Some(method) => s"Resource[${resource.datatype.label}] ${method} ${op.path}"
    }
    prefix + " " + message
  }

  private def validateOperations(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      resource.operations.filter(!_.warnings.isEmpty).map { op =>
        opLabel(resource, op, op.warnings.mkString(", "))
      }
    }
  }

  private def validateParameters(): Seq[String] = {
    val missingNames = internalService.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.parameters.filter(_.name.isEmpty).map { p =>
          opLabel(resource, op, "Missing name")
        }
        op.parameters.filter(_.datatype.isEmpty).map { p =>
          opLabel(resource, op, "Missing type")
        }
      }
    }

    missingNames
  }

  private def validatePathParameters(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      internalService.get.models.find(_.name == resource.datatype.label) match {
        case None => None
        case Some(model: InternalModelForm) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.datatype.map(_.name).isEmpty).map(f => (f.name.get -> f.datatype.get)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.datatype.map(_.name).isEmpty).map(p => (p.name.get -> p.datatype.get)).toMap

            op.namedPathParameters.flatMap { name =>
              val parsedDatatype = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  InternalDatatype(Primitives.String.toString)
                }
              }
              val errorTemplate = opLabel(resource, op, s"path parameter[$name] has an invalid type[%s]. Valid types for path parameters are: ${Primitives.ValidInPath.mkString(", ")}")

              internalService.get.typeResolver.parse(parsedDatatype) match {
                case None => Some(errorTemplate.format(name))

                case Some(Datatype.List(_)) => Some(errorTemplate.format("list"))
                case Some(Datatype.Map(_)) => Some(errorTemplate.format("map"))
                case Some(Datatype.Singleton(t)) => {
                  isTypeValidInPath(t) match {
                    case true => None
                    case false => Some(errorTemplate.format(t.name))
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private def isTypeValidInPath(t: Type): Boolean = {
    t.typeKind match {
      case Kind.Primitive => {
        Primitives.validInPath(t.name)
      }
      case Kind.Model | Kind.Union => {
        // We do not support models in path parameters
        false
      }
      case Kind.Enum => {
        // Serializes as a string
        true
      }
    }
  }

  private def validatePathParametersAreRequired(): Seq[String] = {
    internalService.get.resources.flatMap { resource =>
      internalService.get.models.find(_.name == resource.datatype.label) match {
        case None => None
        case Some(model: InternalModelForm) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.datatype.map(_.name).isEmpty).map(f => (f.name.get -> f.required)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.datatype.map(_.name).isEmpty).map(p => (p.name.get -> p.required)).toMap

            op.namedPathParameters.flatMap { name =>
              val isRequired = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  true
                }
              }

              if (isRequired) {
                None
              } else {
                Some(s"Resource[${resource.datatype.label}] ${op.method.getOrElse("")} path parameter[$name] is specified as optional. All path parameters are required")
              }
            }
          }
        }
      }
    }
  }
}
