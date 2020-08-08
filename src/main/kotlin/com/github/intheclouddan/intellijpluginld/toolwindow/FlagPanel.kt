package com.github.intheclouddan.intellijpluginld.toolwindow

import com.github.intheclouddan.intellijpluginld.FlagStore
import com.github.intheclouddan.intellijpluginld.action.PopupDialogAction
import com.github.intheclouddan.intellijpluginld.messaging.FlagNotifier
import com.github.intheclouddan.intellijpluginld.messaging.MessageBusService
import com.github.intheclouddan.intellijpluginld.settings.LaunchDarklyConfig
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.*
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.SimpleTreeStructure
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.CardLayout
import javax.swing.JPanel


private const val SPLITTER_PROPERTY = "BuildAttribution.Splitter.Proportion"

/*
 * FlagPanel renders the ToolWindow Flag Treeview and associated action buttons.
 */
class FlagPanel(private val myProject: Project, messageBusService: MessageBusService) : SimpleToolWindowPanel(false, false), Disposable {
    private val settings = LaunchDarklyConfig.getInstance(myProject)

    private fun createTreeStructure(): SimpleTreeStructure {
        val getFlags = myProject.service<FlagStore>()
        val rootNode = RootNode(getFlags.flags, getFlags.flagConfigs, settings)
        return FlagTreeStructure(myProject, rootNode)
    }

    override fun dispose() {}

    private fun initTree(model: AsyncTreeModel): Tree {
        val tree = Tree(model)
        tree.isRootVisible = false
        TreeSpeedSearch(tree).comparator = SpeedSearchComparator(false)
        TreeUtil.installActions(tree)
        return tree
    }

    fun start() {
        val treeStucture = createTreeStructure()
        val treeModel = StructureTreeModel(treeStucture, this)

        var reviewTreeBuilder = AsyncTreeModel(treeModel, this)
        val tree = initTree(reviewTreeBuilder)

        val componentsSplitter = OnePixelSplitter(SPLITTER_PROPERTY, 0.33f)
        componentsSplitter.setHonorComponentsMinimumSize(true)
        componentsSplitter.setHonorComponentsMinimumSize(true)
        componentsSplitter.firstComponent = JPanel(CardLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(tree, SideBorder.NONE), "Tree")
        }
        setContent(componentsSplitter)
        val actionManager: ActionManager = ActionManager.getInstance()
        val actionGroup = DefaultActionGroup()
        actionGroup.addAction(PopupDialogAction())
        val actionToolbar: ActionToolbar = actionManager.createActionToolbar("ACTION_TOOLBAR", actionGroup, true)
        setToolbar(actionToolbar.component)
    }

    init {
        if (settings.isConfigured()) {
            start()
        }
        try {
            myProject.messageBus.connect().subscribe(messageBusService.flagsUpdatedTopic,
                    object : FlagNotifier {
                        override fun notify(isConfigured: Boolean) {
                            println("notified For Flags")
                            if (isConfigured) {
                                println("rendering")
                                start()
                            } else {
                                println("notified")
                                val notification = Notification("ProjectOpenNotification", "LaunchDarkly",
                                        String.format("LaunchDarkly Plugin is not configured"), NotificationType.WARNING);
                                notification.notify(myProject);
                            }
                        }
                    })
        } catch (err: Error) {
            println(err)
            println("something went wrong")
        }
    }
}