package com.github.intheclouddan.intellijpluginld.settings

import com.github.intheclouddan.intellijpluginld.LaunchDarklyApiClient
import com.github.intheclouddan.intellijpluginld.messaging.DefaultMessageBusService
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.layout.PropertyBinding
import com.intellij.ui.layout.panel
import com.intellij.ui.layout.withTextBinding
import com.launchdarkly.api.ApiException
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.JPasswordField

/*
 * Maintain state of what LaunchDarkly Project to connect to.
 */
@State(name = "LaunchDarklyConfig", storages = [Storage("launchdarkly.xml")])
open class LaunchDarklyConfig(project: Project) : PersistentStateComponent<LaunchDarklyConfig.ConfigState> {
    val project: Project = project
    var ldState: ConfigState = ConfigState()

    companion object {
        fun getInstance(project: Project): LaunchDarklyConfig {
            return ServiceManager.getService(project, LaunchDarklyConfig(project)::class.java)
        }
    }

    override fun getState(): ConfigState {
        return ldState
    }

    override fun loadState(state: ConfigState) {
        ldState = state
    }

    // Not in working state.
    fun isConfigured(): Boolean {
        if (ldState.project == "" || ldState.environment == "" || ldState.authorization == "") {
            return false
        }
        return true
    }


    fun credentialNamespace(): String {
        val CRED_NAMESPACE = "launchdarkly-intellij"
        return CRED_NAMESPACE + "-" + project.name
    }

    class ConfigState {
        private val key = "apiKey"
        private val credentialAttributes: CredentialAttributes =
                CredentialAttributes(generateServiceName(
                        "launchdarkly-intellij",
                        key
                ))
        var project = ""
        var environment = ""
        var refreshRate: Int = 120

        // Stored in System Credential store
        var authorization: String
            get() = PasswordSafe.instance.getPassword(credentialAttributes) ?: ""
            set(value) {
                val credentials = Credentials("", value)
                PasswordSafe.instance.set(credentialAttributes, credentials)
            }
    }
}

class LaunchDarklyConfigurable(private val project: Project) : BoundConfigurable(displayName = "LaunchDarkly Plugin") {
    private val apiField = JPasswordField()
    private val messageBusService = project.service<DefaultMessageBusService>()
    private val projectApi = LaunchDarklyApiClient.projectInstance(project)
    private val settings = LaunchDarklyConfig.getInstance(project).ldState
    private val origApiKey = settings.authorization

    private var modified = false
    private var panel = JPanel()
    private var apiUpdate = false
    private var lastSelectedProject = ""
    lateinit var projectContainer: MutableList<com.launchdarkly.api.model.Project>
    lateinit var environmentContainer: com.launchdarkly.api.model.Project

    private lateinit var defaultMessage: String
    private lateinit var projectBox: DefaultComboBoxModel<String>
    private lateinit var environmentBox: DefaultComboBoxModel<String>

    init {
        try {
            projectContainer = projectApi.projects.items
            if (projectContainer.size > 0) {
                environmentContainer = projectContainer.find { it.key == settings.project }
                        ?: projectContainer.firstOrNull() as com.launchdarkly.api.model.Project
            }
        } catch (err: ApiException) {
            defaultMessage = "Check API Key"
        }
    }

    override fun createPanel(): DialogPanel {
        panel = panel {
            commentRow("Add your LaunchDarkly API Key and click Apply. Project and Environment selections will populate based on key permissions.")
            row("API Key:") { apiField().withTextBinding(PropertyBinding({ settings.authorization }, { settings.authorization = it })) }
            //row("Refresh Rate(in Minutes):") { intTextField(settings::refreshRate, 0, 0..1440) }

            if (::projectContainer.isInitialized) {
                projectBox = DefaultComboBoxModel<String>(projectContainer.map { it -> it.key }.toTypedArray())
            } else {
                projectBox = DefaultComboBoxModel<String>(arrayOf(defaultMessage))
            }
            row("Project") {
                comboBox(projectBox, settings::project, renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
                    label.text = value
                })
            }

            if (::environmentContainer.isInitialized) {
                environmentBox = DefaultComboBoxModel<String>(environmentContainer.environments.map { it -> it.key }.toTypedArray())
            } else {
                environmentBox = DefaultComboBoxModel<String>(arrayOf("Please select a Project"))
            }
            row("Environments:") {
                comboBox(environmentBox, settings::environment, renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
                    label.text = value
                })
            }
        }
        return panel as DialogPanel
    }

    override fun isModified(): Boolean {
        if (settings.authorization != origApiKey && !apiUpdate) {
            try {
                projectContainer = LaunchDarklyApiClient.projectInstance(project, settings.authorization).projects.items
                projectBox.removeAllElements()
                if (projectBox.selectedItem == null || projectBox.selectedItem.toString() == "Check API Key") {
                    projectBox.selectedItem = projectContainer.map { it.key }.firstOrNull()
                }
                projectBox.addAll(projectContainer.map { it.key })
                apiUpdate = true
            } catch (err: Error) {
                println(err)
            }
        }

        if (::projectContainer.isInitialized && lastSelectedProject != projectBox.selectedItem.toString()) {
            try {
                environmentContainer = projectContainer.find { it.key == projectBox.selectedItem.toString() }!!
                val envMap = environmentContainer.environments.map { it.key }
                if (::environmentBox.isInitialized) {
                    environmentBox.removeAllElements()
                    environmentBox.addAll(envMap)
                    if (environmentBox.selectedItem == null || environmentBox.selectedItem.toString() == "Please select a Project") {
                        environmentBox.selectedItem = envMap.firstOrNull()
                    }

                }
                lastSelectedProject = projectBox.selectedItem.toString()
            } catch (err: Error) {
                println(err)
            }
        }

        if (::projectBox.isInitialized || settings.project != projectBox.selectedItem.toString()) {
            modified = true
        }

        if (::environmentBox.isInitialized) {
            if (settings.environment != environmentBox.selectedItem.toString()) {
                modified = true
            }
        }

        val sup = super.isModified()
        return modified || sup
    }

    override fun apply() {
        super.apply()
        if (modified && origApiKey != "") {
            val publisher = project.messageBus.syncPublisher(messageBusService.configurationEnabledTopic)
            publisher.notify(true)
            println("notifying")
        }

        if (settings.project != projectBox.selectedItem.toString()) {
            settings.project = projectBox.selectedItem.toString()
        }


    }

}