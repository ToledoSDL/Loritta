package net.perfectdreams.loritta.website.routes.dashboard

import com.github.salomonbrys.kotson.nullString
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.set
import com.google.gson.JsonObject
import com.mrpowergamerbr.loritta.network.Databases
import com.mrpowergamerbr.loritta.tables.Dailies
import com.mrpowergamerbr.loritta.tables.Profiles
import com.mrpowergamerbr.loritta.utils.*
import com.mrpowergamerbr.loritta.utils.locale.BaseLocale
import io.ktor.application.ApplicationCall
import io.ktor.request.header
import io.ktor.request.host
import io.ktor.request.path
import io.ktor.response.respondRedirect
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set
import mu.KotlinLogging
import net.dv8tion.jda.api.Permission
import net.perfectdreams.loritta.platform.discord.LorittaDiscord
import net.perfectdreams.loritta.tables.BlacklistedGuilds
import net.perfectdreams.loritta.utils.DiscordUtils
import net.perfectdreams.loritta.website.routes.LocalizedRoute
import net.perfectdreams.loritta.website.session.LorittaJsonWebSession
import net.perfectdreams.loritta.website.utils.extensions.respondHtml
import net.perfectdreams.loritta.website.utils.extensions.toJson
import net.perfectdreams.loritta.website.utils.extensions.trueIp
import net.perfectdreams.temmiediscordauth.TemmieDiscordAuth
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class DashboardAuthRoute(loritta: LorittaDiscord) : LocalizedRoute(loritta, "/dashboardauth") {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun onLocalizedRequest(call: ApplicationCall, locale: BaseLocale) {
		val hostHeader = call.request.host()

		val state = call.parameters["state"]
		val guildId = call.parameters["guild_id"]
		val code = call.parameters["code"]
		val fromMaster = call.parameters["from_master"]

		println("Dashboard Auth Route")
		val session: LorittaJsonWebSession = call.sessions.get<LorittaJsonWebSession>() ?: LorittaJsonWebSession.empty()
		val discordAuth = session.getDiscordAuthFromJson()

		// Caso o usuário utilizou o invite link que adiciona a Lori no servidor, terá o parâmetro "guild_id" na URL
		// Se o parâmetro exista, vamos redirecionar!
		if (code == null) {
			if (discordAuth == null) {
				if (call.request.header("User-Agent") == Constants.DISCORD_CRAWLER_USER_AGENT) {
					call.respondHtml(WebsiteUtils.getDiscordCrawlerAuthenticationPage())
				} else {
					val state = JsonObject()
					state["redirectUrl"] = "https://$hostHeader" + call.request.path()
					call.respondRedirect(com.mrpowergamerbr.loritta.utils.loritta.discordInstanceConfig.discord.authorizationUrl + "&state=${Base64.getEncoder().encodeToString(state.toString().toByteArray()).encodeToUrl()}", false)
				}
			}
		} else {
			val storedUserIdentification = null /* session.getCachedIdentification() */

			val userIdentification = if (code == "from_master" && storedUserIdentification != null) {
				// Veio do master cluster, vamos apenas tentar autenticar com os dados existentes!
				storedUserIdentification
			} else {
				val auth = TemmieDiscordAuth(
						com.mrpowergamerbr.loritta.utils.loritta.discordConfig.discord.clientId,
						com.mrpowergamerbr.loritta.utils.loritta.discordConfig.discord.clientSecret,
						code,
						"https://$hostHeader/dashboardauth",
						listOf("identify", "guilds", "email", "guilds.join")
				)

				auth.doTokenExchange()
				val userIdentification = auth.getUserIdentification()

				call.sessions.set(session.copy(storedDiscordAuthTokens = auth.toJson()))

				userIdentification
			}!!

			// Verificar se o usuário é (possivelmente) alguém que foi banido de usar a Loritta
			val trueIp = call.request.trueIp
			val dailiesWithSameIp = transaction(Databases.loritta) {
				Dailies.select {
					(Dailies.ip eq trueIp)
				}.toMutableList()
			}

			val userIds = dailiesWithSameIp.map { it[Dailies.id] }.distinct()

			val bannedProfiles = transaction(Databases.loritta) {
				Profiles.select { Profiles.id inList userIds and Profiles.isBanned }
						.toMutableList()
			}

			if (bannedProfiles.isNotEmpty())
				logger.warn { "User ${userIdentification.id} has banned accounts in ${trueIp}! IDs: ${bannedProfiles.joinToString(transform = { it[Profiles.id].toString() })}" }

			if (state != null) {
				// state = base 64 encoded JSON
				val decodedState = Base64.getDecoder().decode(state).toString(Charsets.UTF_8)
				val jsonState = jsonParser.parse(decodedState).obj
				val redirectUrl = jsonState["redirectUrl"].nullString

				if (redirectUrl != null) {
					call.respondRedirect(redirectUrl, false)
					return
				}
			}

			if (guildId != null) {
				if (fromMaster == null) {
					val cluster = DiscordUtils.getLorittaClusterForGuildId(guildId.toLong())

					if (cluster.getUrl() != hostHeader) {
						logger.info { "Received guild $guildId via OAuth2 scope, but the guild isn't in this cluster! Redirecting to where the user should be... $cluster" }

						// Vamos redirecionar!
						call.respondRedirect("https://${cluster.getUrl()}/dashboard?guild_id=${guildId}&code=from_master", true)
						return
					}
				}

				logger.info { "Received guild $guildId via OAuth2 scope, sending DM to the guild owner..." }
				var guildFound = false
				var tries = 0
				val maxGuildTries = com.mrpowergamerbr.loritta.utils.loritta.config.loritta.website.maxGuildTries

				while (!guildFound && maxGuildTries > tries) {
					val guild = lorittaShards.getGuildById(guildId)

					if (guild != null) {
						logger.info { "Guild ${guild} was successfully found after $tries tries! Yay!!" }

						val serverConfig = com.mrpowergamerbr.loritta.utils.loritta.getOrCreateServerConfig(guild.idLong)

						// Agora nós iremos pegar o locale do servidor
						val locale = com.mrpowergamerbr.loritta.utils.loritta.getLegacyLocaleById(serverConfig.localeId)

						val userId = userIdentification.id

						val user = lorittaShards.retrieveUserById(userId)

						if (user != null) {
							val member = guild.getMember(user)

							if (member != null) {
								// E, se o membro não for um bot e possui permissão de gerenciar o servidor ou permissão de administrador...
								if (!user.isBot && (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.ADMINISTRATOR))) {
									// Verificar coisas antes de adicionar a Lori
									val blacklisted = transaction(Databases.loritta) {
										BlacklistedGuilds.select {
											BlacklistedGuilds.id eq guild.idLong
										}.firstOrNull()
									}

									if (blacklisted != null) {
										val blacklistedReason = blacklisted[BlacklistedGuilds.reason]

										// Envie via DM uma mensagem falando sobre o motivo do ban
										val message = locale["LORITTA_BlacklistedServer", blacklistedReason]

										user.openPrivateChannel().queue {
											it.sendMessage(message).queue({
												guild.leave().queue()
											}, {
												guild.leave().queue()
											})
										}
										return
									}

									val profile = com.mrpowergamerbr.loritta.utils.loritta.getOrCreateLorittaProfile(guild.owner!!.user.id)
									if (profile.isBanned) { // Dono blacklisted
										// Envie via DM uma mensagem falando sobre a Loritta!
										val message = locale["LORITTA_OwnerLorittaBanned", guild.owner?.user?.asMention, profile.bannedReason
												?: "???"]

										user.openPrivateChannel().queue {
											it.sendMessage(message).queue({
												guild.leave().queue()
											}, {
												guild.leave().queue()
											})
										}
										return
									}

									// Envie via DM uma mensagem falando sobre a Loritta!
									val message = locale["LORITTA_ADDED_ON_SERVER", user.asMention, guild.name, com.mrpowergamerbr.loritta.utils.loritta.instanceConfig.loritta.website.url, locale["LORITTA_SupportServerInvite"], com.mrpowergamerbr.loritta.utils.loritta.legacyCommandManager.commandMap.size + com.mrpowergamerbr.loritta.utils.loritta.commandManager.commands.size, "${com.mrpowergamerbr.loritta.utils.loritta.instanceConfig.loritta.website.url}donate"]

									user.openPrivateChannel().queue {
										it.sendMessage(message).queue()
									}
								}
							}
						}
						guildFound = true // Servidor detectado, saia do loop!
					} else {
						tries++
						logger.warn { "Received guild $guildId via OAuth2 scope, but I'm not in that guild yet! Waiting for 1s... Tries: ${tries}" }
						Thread.sleep(1_000)
					}
				}

				if (tries == maxGuildTries) {
					// oof
					logger.warn { "Received guild $guildId via OAuth2 scope, we tried ${maxGuildTries} times, but I'm not in that guild yet! Telling the user about the issue..." }

					call.respondHtml(
							"""
							|<p>Parece que você tentou me adicionar no seu servidor, mas mesmo assim eu não estou nele!</p>
							|<ul>
							|<li>Tente me readicionar, as vezes isto acontece devido a um delay entre o tempo até o Discord atualizar os servidores que eu estou. <a href="https://loritta.website/dashboard">https://loritta.website/dashboard</a></li>
							|<li>
							|Verifique o registro de auditoria do seu servidor, alguns bots expulsam/banem ao adicionar novos bots. Caso isto tenha acontecido, expulse o bot que me puniu e me readicione!
							|<ul>
							|<li>
							|<b>Em vez de confiar em um bot para "proteger" o seu servidor:</b> Veja quem possui permissão de administrador ou de gerenciar servidores no seu servidor, eles são os únicos que conseguem adicionar bots no seu servidor. Existem boatos que existem "bugs que permitem adicionar bots sem permissão", mas isto é mentira.
							|</li>
							|</ul>
							|</li>
							|</ul>
							|<p>Desculpe pela inconveniência ;w;</p>
						""".trimMargin())
					return
				}

				call.respondRedirect("https://$hostHeader/dashboard/configure/${guildId}", false)
				return
			}

			call.respondRedirect("https://$hostHeader/dashboard", false) // Redirecionar para a dashboard, mesmo que nós já estejamos lá... (remove o "code" da URL)
		}
	}
}