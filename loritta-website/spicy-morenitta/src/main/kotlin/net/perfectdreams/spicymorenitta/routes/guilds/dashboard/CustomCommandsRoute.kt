package net.perfectdreams.spicymorenitta.routes.guilds.dashboard

import jq
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.dom.create
import kotlinx.html.js.onClickFunction
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.list
import kotlinx.serialization.modules.SerializersModule
import net.perfectdreams.loritta.serializable.CustomCommand
import net.perfectdreams.spicymorenitta.SpicyMorenitta
import net.perfectdreams.spicymorenitta.application.ApplicationCall
import net.perfectdreams.spicymorenitta.locale
import net.perfectdreams.spicymorenitta.routes.UpdateNavbarSizePostRender
import net.perfectdreams.spicymorenitta.utils.*
import net.perfectdreams.spicymorenitta.utils.DashboardUtils.launchWithLoadingScreenAndFixContent
import net.perfectdreams.spicymorenitta.utils.DashboardUtils.switchContentAndFixLeftSidebarScroll
import net.perfectdreams.spicymorenitta.utils.customcommands.AutoroleCustomCommand
import net.perfectdreams.spicymorenitta.utils.customcommands.CustomCommandData
import net.perfectdreams.spicymorenitta.utils.customcommands.CustomCommandWrapper
import net.perfectdreams.spicymorenitta.utils.customcommands.TextCustomCommand
import org.w3c.dom.*
import kotlin.browser.document
import kotlin.dom.clear
import kotlin.js.Json

class CustomCommandsRoute(val m: SpicyMorenitta) : UpdateNavbarSizePostRender("/guild/{guildid}/configure/custom-commands") {
	companion object {
		private const val LOCALE_PREFIX = "modules.customCommands"
	}

	val json = kotlinx.serialization.json.Json(
			context = SerializersModule {
				polymorphic(CustomCommandData::class) {
					TextCustomCommand::class with TextCustomCommand.serializer()
					AutoroleCustomCommand::class with AutoroleCustomCommand.serializer()
				}
			}
	)

	@Serializable
	class PartialGuildConfiguration(
			val customCommands: Array<CustomCommand>
	)

	val customCommands = mutableListOf<CustomCommand>()

	override fun onUnload() {
		customCommands.clear()
	}

	@ImplicitReflectionSerializer
	override fun onRender(call: ApplicationCall) {
		launchWithLoadingScreenAndFixContent(call) {
			val guild = DashboardUtils.retrievePartialGuildConfiguration<PartialGuildConfiguration>(call.parameters["guildid"]!!, "custom_commands")
			customCommands.addAll(guild.customCommands)
			switchContentAndFixLeftSidebarScroll(call)

			document.select<HTMLButtonElement>("#save-button").onClick {
				prepareSave()
			}

			val addNewEntryButton = document.select<HTMLButtonElement>("#add-new-entry")
			addNewEntryButton.onClick {
				val modal = TingleModal(
						TingleOptions(
								footer = true,
								cssClass = arrayOf("tingle-modal--overflow")
						)
				)

				modal.addFooterBtn("<i class=\"fas fa-times\"></i> Fechar", "button-discord pure-button button-discord-modal button-discord-modal-secondary-action") {
					modal.close()
				}

				modal.setContent(
						document.create.div {
							div(classes = "category-name") {
								+ "Qual tipo de comando você deseja criar?"
							}

							div {
								style = "text-align: center;"

								div("button-discord button-discord-info pure-button") {
									style = "text-align: left; line-height: 1; box-sizing: border-box !important; width: 100%;"
									div {
										style = "font-size: 1.5em; font-weight: bolder;"
										+"Comando de Texto"
									}
									div {
										+"Um comando que envia uma mensagem pré-definida ao utilizá-lo"
									}

									onClickFunction = {
										modal.close()

										openTextCommandModal("comando", "Loritta é muito fofa!")
									}
								}
							}
						}
				)

				modal.open()
			}

			val stuff = document.select<HTMLDivElement>("#level-stuff")

			val easyCommands = customCommands.filter { it.code.startsWith("// Loritta Auto Generated Custom Command - Do not edit!") }
					.map { it to json.parse(CustomCommandWrapper.serializer(), it.code.lines()[1].removePrefix("// ")) }

			debug("There are ${easyCommands.size} easy commands")

			stuff.append {
				div(classes = "custom-commands") {}
			}

			updateCustomCommandsList()
		}
	}

	@ImplicitReflectionSerializer
	private fun updateCustomCommandsList() {
		// For now only easy commands
		val easyCommands = customCommands.filter { it.code.startsWith("// Loritta Auto Generated Custom Command - Do not edit!") }
		val trackedDiv = document.select<HTMLDivElement>(".custom-commands")

		trackedDiv.clear()

		trackedDiv.append {
			if (easyCommands.isEmpty()) {
				div {
					style = "text-align: center;font-size: 2em;opacity: 0.7;"
					div {
						img(src = "https://loritta.website/assets/img/blog/lori_calca.gif") {
							style = "width: 20%; filter: grayscale(100%);"
						}
					}
					+ "${locale["website.empty"]}${locale.getList("website.funnyEmpty").random()}"
				}
			} else {
				for (command in easyCommands) {
					createCustomCommandEntry(command)
				}
			}
		}
	}

	@ImplicitReflectionSerializer
	fun TagConsumer<HTMLElement>.createCustomCommandEntry(customCommand: CustomCommand) {
		val customCommandWrapper = if (customCommand.code.startsWith("// Loritta Auto Generated Custom Command - Do not edit!")) {
			json.parse(CustomCommandWrapper.serializer(), customCommand.code.lines()[1].removePrefix("// "))
		} else null

		this.div(classes = "discord-generic-entry timer-entry") {
			img(classes = "amino-small-image") {
				style = "width: 6%; height: auto; float: left; position: relative; bottom: 8px;"
				src = "/assets/img/file_code.png"
			}

			div(classes = "pure-g") {
				div(classes = "pure-u-1 pure-u-md-18-24") {
					div {
						style = "margin-left: 10px; margin-right: 10;"
						div(classes = "amino-title entry-title") {
							style = "font-family: Whitney,Helvetica Neue,Helvetica,Arial,sans-serif;"
							+ customCommand.label
						}
						div(classes = "amino-title toggleSubText") {
							+ if (customCommandWrapper != null)
								when (customCommandWrapper.data) {
									is TextCustomCommand -> "Comando de Texto"
									else -> "???"
								}
							else "Comando em Kotlin"
						}
					}
				}
				div(classes = "pure-u-1 pure-u-md-6-24 vertically-centered-content") {
					button(classes = "button-discord button-discord-edit pure-button delete-button") {
						style = "margin-right: 8px; min-width: 0px;"

						onClickFunction = {
							customCommands.remove(customCommand)
							updateCustomCommandsList()
						}

						i(classes = "fas fa-trash") {}
					}
					button(classes = "button-discord button-discord-edit pure-button edit-button") {
						+ "Editar"

						onClickFunction = {
							customCommands.remove(customCommand)

							if (customCommandWrapper != null) {
								val customCommandData = customCommandWrapper.data

								when (customCommandData) {
									is TextCustomCommand -> openTextCommandModal(customCommand.label, customCommandData.text)
								}
							}
						}
					}
				}
			}

		}
	}

	@ImplicitReflectionSerializer
	fun openTextCommandModal(label: String, text: String) {
		val modal = TingleModal(
				TingleOptions(
						footer = true,
						cssClass = arrayOf("tingle-modal--overflow")
				)
		)

		modal.addFooterBtn("Salvar", "button-discord button-discord-info pure-button button-discord-modal") {
			val textAreaText = visibleModal.select<HTMLTextAreaElement>(".command-text")
					.value

			println(textAreaText)

			val code = """// Loritta Auto Generated Custom Command - Do not edit!
								|// ${json.toJson(CustomCommandWrapper(TextCustomCommand(textAreaText)))}
								|sendMessage(${stringLiteralWithQuotes(textAreaText)})
							""".trimMargin()
			println(code)

			customCommands.add(
					CustomCommand(
							visibleModal.select<HTMLInputElement>(".command-label").value,
							code
					)
			)

			updateCustomCommandsList()

			modal.close()
		}

		modal.setContent(
				document.create.div {
					div(classes = "category-name") {
						+ "Comando de Texto"
					}

					h5(classes = "section-title") {
						+ "Nome do Comando"
					}

					input(InputType.text, classes = "command-label") {
						value = label
					}

					h5(classes = "section-title") {
						+ "Mensagem"
					}

					textArea(classes = "command-text") {
						style = "box-sizing: border-box !important; width: 100%;"

						+ text
					}
				}
		)
		modal.open()
		modal.trackOverflowChanges(m)

		LoriDashboard.configureTextArea(
				jq(".tingle-modal--visible .command-text"),
				true,
				null,
				false,
				null,
				false,
				showTemplates = false
		)
	}

	@JsName("prepareSave")
	fun prepareSave() {
		SaveUtils.prepareSave("custom_commands", extras = {
			it["entries"] = kotlin.js.JSON.parse<Array<Json>>(kotlinx.serialization.json.Json.stringify(CustomCommand.serializer().list, customCommands))
		})
	}

	/** Returns the string literal representing `value`, including wrapping double quotes.  */
	internal fun stringLiteralWithQuotes(
			value: String,
			escapeDollarSign: Boolean = true,
			isConstantContext: Boolean = false
	): String {
		if (!isConstantContext && '\n' in value) {
			val result = StringBuilder(value.length + 32)
			result.append("\"\"\"\n|")
			var i = 0
			while (i < value.length) {
				val c = value[i]
				if (value.regionMatches(i, "\"\"\"", 0, 3)) {
					// Don't inadvertently end the raw string too early
					result.append("\"\"\${'\"'}")
					i += 2
				} else if (c == '\n') {
					// Add a '|' after newlines. This pipe will be removed by trimMargin().
					result.append("\n|")
				} else if (c == '$' && escapeDollarSign) {
					// Escape '$' symbols with ${'$'}.
					result.append("\${\'\$\'}")
				} else {
					result.append(c)
				}
				i++
			}
			// If the last-emitted character wasn't a margin '|', add a blank line. This will get removed
			// by trimMargin().
			if (!value.endsWith("\n")) result.append("\n")
			result.append("\"\"\".trimMargin()")
			return result.toString()
		} else {
			val result = StringBuilder(value.length + 32)
			// using pre-formatted strings allows us to get away with not escaping symbols that would
			// normally require escaping, e.g. "foo ${"bar"} baz"
			if (escapeDollarSign) result.append('"') else result.append("\"\"\"")
			for (i in 0 until value.length) {
				val c = value[i]
				// Trivial case: single quote must not be escaped.
				if (c == '\'') {
					result.append("'")
					continue
				}
				// Trivial case: double quotes must be escaped.
				if (c == '\"' && escapeDollarSign) {
					result.append("\\\"")
					continue
				}
				// Trivial case: $ signs must be escaped.
				if (c == '$' && escapeDollarSign) {
					result.append("\${\'\$\'}")
					continue
				}
				// Default case: just let character literal do its work.
				result.append(characterLiteralWithoutSingleQuotes(c))
				// Need to append indent after linefeed?
			}
			if (escapeDollarSign) result.append('"') else result.append("\"\"\"")
			return result.toString()
		}
	}

	// see https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.10.6
	internal fun characterLiteralWithoutSingleQuotes(c: Char) = when {
		c == '\b' -> "\\b" // \u0008: backspace (BS)
		c == '\t' -> "\\t" // \u0009: horizontal tab (HT)
		c == '\n' -> "\\n" // \u000a: linefeed (LF)
		c == '\r' -> "\\r" // \u000d: carriage return (CR)
		c == '\"' -> "\"" // \u0022: double quote (")
		c == '\'' -> "\\'" // \u0027: single quote (')
		c == '\\' -> "\\\\" // \u005c: backslash (\)
		// c.isIsoControl -> String.format("\\u%04x", c.toInt())
		else -> c.toString()
	}
}