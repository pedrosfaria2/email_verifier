package io.initialcapacity.emailverifier.registrationserver

import com.rabbitmq.client.ConnectionFactory
import io.initialcapacity.emailverifier.databasesupport.DatabaseTemplate
import io.initialcapacity.emailverifier.rabbitsupport.*
import io.initialcapacity.emailverifier.registration.RegistrationConfirmationService
import io.initialcapacity.emailverifier.registration.RegistrationDataGateway
import io.initialcapacity.emailverifier.registration.register
import io.initialcapacity.emailverifier.registrationrequest.RegistrationRequestDataGateway
import io.initialcapacity.emailverifier.registrationrequest.RegistrationRequestService
import io.initialcapacity.emailverifier.registrationrequest.UuidProvider
import io.initialcapacity.emailverifier.registrationrequest.registrationRequest
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.util.*

class App

private val logger = LoggerFactory.getLogger(App::class.java)

fun main(): Unit = runBlocking {
    val port = System.getenv("PORT")?.toInt() ?: 8081
    val rabbitUrl = System.getenv("RABBIT_URL")?.let(::URI)
        ?: throw RuntimeException("Please set the RABBIT_URL environment variable")
    val databaseUrl = System.getenv("DATABASE_URL")
        ?: throw RuntimeException("Please set the DATABASE_URL environment variable")

    val dbConfig = DatabaseConfiguration(databaseUrl)
    val dbTemplate = DatabaseTemplate(dbConfig.db)

    val connectionFactory = buildConnectionFactory(rabbitUrl)
    val registrationRequestGateway = RegistrationRequestDataGateway(dbTemplate)
    val registrationGateway = RegistrationDataGateway(dbTemplate)

    // Define a notification exchange of type direct
    val registrationNotificationExchange = RabbitExchange(
        name = "registration-notification-exchange",
        type = "direct",
        routingKeyGenerator = { _: String -> "42" },
    )
    // Define a queue for notifications
    val registrationNotificationQueue = RabbitQueue("registration-notification")
    // Bind the queue to the exchange
    connectionFactory.declareAndBind(exchange = registrationNotificationExchange, queue = registrationNotificationQueue, routingKey = "42")

    // Define a consistent hash exchange for registration requests
    val registrationRequestExchange = RabbitExchange(
        name = "registration-request-consistent-hash-exchange",
        type = "x-consistent-hash",
        routingKeyGenerator = { message: String -> calculateRoutingKey(message) },
    )
    // Read the queue name from the environment, default to "registration-request"
    val registrationRequestQueueName = System.getenv("REGISTRATION_REQUEST_QUEUE") ?: "registration-request"
    val registrationRequestQueue = RabbitQueue(registrationRequestQueueName)
    // Read the routing key from the environment, default to "42"
    val routingKey = System.getenv("REGISTRATION_REQUEST_ROUTING_KEY") ?: "42"
    // Bind the queue to the consistent hash exchange
    connectionFactory.declareAndBind(exchange = registrationRequestExchange, queue = registrationRequestQueue, routingKey = routingKey)

    // Start listening for registration requests
    listenForRegistrationRequests(
        connectionFactory,
        registrationRequestGateway,
        registrationNotificationExchange,
        registrationRequestQueue
    )
    // Start the registration server
    registrationServer(
        port,
        registrationRequestGateway,
        registrationGateway,
        connectionFactory,
        registrationRequestExchange
    ).start()
}

fun registrationServer(
    port: Int,
    registrationRequestGateway: RegistrationRequestDataGateway,
    registrationGateway: RegistrationDataGateway,
    connectionFactory: ConnectionFactory,
    registrationRequestExchange: RabbitExchange,
) = embeddedServer(
    factory = Jetty,
    port = port,
    module = { module(registrationRequestGateway, registrationGateway, connectionFactory, registrationRequestExchange) }
)

fun Application.module(
    registrationRequestGateway: RegistrationRequestDataGateway,
    registrationGateway: RegistrationDataGateway,
    connectionFactory: ConnectionFactory,
    registrationRequestExchange: RabbitExchange,
) {
    install(Resources)
    install(CallLogging)
    install(AutoHeadResponse)
    install(ContentNegotiation) {
        json()
    }

    val publishRequest = publish(connectionFactory, registrationRequestExchange)

    install(Routing) {
        info()
        registrationRequest(publishRequest)
        register(RegistrationConfirmationService(registrationRequestGateway, registrationGateway))
    }
}

fun CoroutineScope.listenForRegistrationRequests(
    connectionFactory: ConnectionFactory,
    registrationRequestDataGateway: RegistrationRequestDataGateway,
    registrationNotificationExchange: RabbitExchange,
    registrationRequestQueue: RabbitQueue,
    uuidProvider: UuidProvider = { UUID.randomUUID() },
) {
    val publishNotification = publish(connectionFactory, registrationNotificationExchange)

    val registrationRequestService = RegistrationRequestService(
        gateway = registrationRequestDataGateway,
        publishNotification = publishNotification,
        uuidProvider = uuidProvider,
    )

    launch {
        logger.info("listening for registration requests")
        val channel = connectionFactory.newConnection().createChannel()
        listen(queue = registrationRequestQueue, channel = channel) { email ->
            logger.debug("received registration request for {}", email)
            registrationRequestService.generateCodeAndPublish(email)
        }
    }
}

// Calculate the routing key using MD5 hash
fun calculateRoutingKey(message: String): String {
    val md = MessageDigest.getInstance("MD5")
    val hashBytes = md.digest(message.toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}
