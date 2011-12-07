/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.openmole.ide.core.implementation.control

import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import org.openide.DialogDescriptor
import org.openide.DialogDisplayer
import org.openide.NotifyDescriptor
import org.openide.util.Lookup
import org.openmole.core.implementation.execution.local.LocalExecutionEnvironment
import org.openmole.core.implementation.mole.MoleExecution
import org.openmole.core.model.execution.IEnvironment
import org.openmole.core.model.hook.IHook
import org.openmole.core.model.mole.ICapsule
import org.openmole.core.model.mole.IGroupingStrategy
import org.openmole.ide.misc.visualization.PiePlotter
import org.openmole.ide.misc.visualization.XYPlotter
import org.openmole.ide.misc.widget.MigPanel
import org.openmole.core.model.mole.IMoleExecution
import org.openmole.ide.core.implementation.serializer.MoleMaker
import org.openmole.ide.core.model.panel._
import org.openmole.ide.core.model.factory._
import org.openmole.ide.core.model.control.IExecutionManager
import org.openmole.ide.core.model.workflow.IMoleSceneManager
import scala.collection.mutable.HashMap
import scala.swing.Action
import scala.swing.Label
import scala.swing.Menu
import scala.swing.MenuBar
import scala.swing.MenuItem
import scala.swing.Orientation
import scala.swing.ScrollPane
import scala.swing.Separator
import scala.swing.SplitPane
import scala.swing.TabbedPane
import org.openmole.misc.eventdispatcher.EventDispatcher
import org.openmole.misc.workspace.Workspace
import scala.collection.JavaConversions._
import scala.swing.TextArea
import org.openmole.core.model.job.State
import org.openmole.core.model.execution.ExecutionState

class ExecutionManager(manager : IMoleSceneManager) extends TabbedPane with IExecutionManager{
  val logTextArea = new TextArea{columns = 20;rows = 10;editable = false}
  val executionJobExceptionTextArea = new TextArea{columns = 40;rows = 10;editable = false}
  val moleExecutionExceptionTextArea = new TextArea{columns = 40;rows = 10;editable = false}
  override val printStream = new PrintStream(new BufferedOutputStream(new TextAreaOutputStream(logTextArea),1024),true)
  override val (mole, capsuleMapping, prototypeMapping) = MoleMaker.buildMole(manager)
  var moleExecution: IMoleExecution = new MoleExecution(mole)
  var gStrategyPanels= new HashMap[String,(IGroupingStrategyPanelUI,List[(IGroupingStrategy,ICapsule)])]
  var hookPanels= new HashMap[String,(IHookPanelUI,List[IHook])]
  var status = HashMap(State.READY-> new AtomicInteger,
                       State.RUNNING-> new AtomicInteger,
                       State.COMPLETED-> new AtomicInteger,
                       State.FAILED-> new AtomicInteger,
                       State.CANCELED-> new AtomicInteger)
  val wfPiePlotter = new PiePlotter("Worflow execution")
  val envBarPanel = new MigPanel("","[][grow,fill]",""){
    peer.add(wfPiePlotter.panel)
    preferredSize = new Dimension(200,200)}
  val envBarPlotter = new XYPlotter("Environment",3600000,36) {preferredSize = new Dimension(200,200)}
  var environments = new HashMap[IEnvironment,(String,HashMap[ExecutionState.ExecutionState,AtomicInteger])]
  
  val hookMenu = new Menu("Hooks")
  val groupingMenu = new Menu("Grouping")
  Lookup.getDefault.lookupAll(classOf[IHookFactoryUI]).foreach{f=>hookMenu.contents+= new MenuItem(new AddHookRowAction(f))}
  Lookup.getDefault.lookupAll(classOf[IGroupingStrategyFactoryUI]).foreach{f=>groupingMenu.contents+= new MenuItem(new AddGroupingStrategyRowAction(f))}
  val menuBar = new MenuBar{contents.append(hookMenu,groupingMenu)}
  menuBar.minimumSize = new Dimension(menuBar.size.width,30)
  val hookPanel = new MigPanel(""){contents+= (menuBar,"wrap")}
  
  val splitPane = new SplitPane(Orientation.Vertical) {
    leftComponent = new ScrollPane(envBarPanel)
    rightComponent = new ScrollPane(logTextArea)
  }
  
  System.setOut(new PrintStream(new BufferedOutputStream(new TextAreaOutputStream(logTextArea)),true))
  System.setErr(new PrintStream(new BufferedOutputStream(new TextAreaOutputStream(logTextArea)),true))
  
  pages+= new TabbedPane.Page("Settings",hookPanel)
  pages+= new TabbedPane.Page("Execution progress", splitPane)
  pages+= new TabbedPane.Page("Mole execution job errors", moleExecutionExceptionTextArea)
  pages+= new TabbedPane.Page("Execution job errors", executionJobExceptionTextArea)
  
  def start = {
    var canBeRun = true
    if(Workspace.anotherIsRunningAt(Workspace.defaultLocation)) {
      val dd = new DialogDescriptor(new Label("A simulation is currently running.\nTwo simulations can not run concurrently, overwrite ?")
                                    {background = Color.white}.peer,
                                    "Execution warning")
      val result = DialogDisplayer.getDefault.notify(dd)
      if (result.equals(NotifyDescriptor.OK_OPTION)) (new File(Workspace.defaultLocation.getAbsolutePath + "/.running")).delete
      else canBeRun = false
    }
    
    if (canBeRun){
      cancel
      initBarPlotter
      hookPanels.values.foreach(_._2.foreach(_.release))
      val moleE = MoleMaker.buildMoleExecution(mole, 
                                               manager, 
                                               capsuleMapping,
                                               gStrategyPanels.values.map{v=>v._1.saveContent.map(_.coreObject)}.flatten.toList)
      moleExecution = moleE._1
      EventDispatcher.listen(moleExecution,new JobSatusListener(this),classOf[IMoleExecution.OneJobStatusChanged])
      EventDispatcher.listen(moleExecution,new JobCreatedListener(this),classOf[IMoleExecution.OneJobSubmitted])
      EventDispatcher.listen(moleExecution,new ExecutionExceptionListener(this),classOf[IMoleExecution.ExceptionRaised])
      EventDispatcher.listen(moleExecution,new EnvironmentExceptionListener(this),classOf[IMoleExecution.ExceptionRaised])
      moleE._2.foreach(buildEmptyEnvPlotter)
      if(envBarPanel.peer.getComponentCount == 2) envBarPanel.peer.remove(1)
      if (moleE._2.size > 0) {
        envBarPlotter.title(moleE._2.toList(0)._2)
        envBarPanel.peer.add(envBarPlotter.panel) 
      }
      initPieChart
      hookPanels.keys.foreach{commitHook}
      repaint 
      revalidate
      moleExecution.start
    }
  }
    
  def cancel = moleExecution.cancel
  
  def initBarPlotter {
    environments.clear
    buildEmptyEnvPlotter((LocalExecutionEnvironment.asInstanceOf[IEnvironment],"Local"))
  }

  def buildEmptyEnvPlotter(e: (IEnvironment,String)) = {
    val m = HashMap(ExecutionState.SUBMITTED-> new AtomicInteger,
                    ExecutionState.READY-> new AtomicInteger,
                    ExecutionState.RUNNING-> new AtomicInteger,
                    ExecutionState.DONE-> new AtomicInteger,
                    ExecutionState.FAILED->new AtomicInteger,
                    ExecutionState.KILLED-> new AtomicInteger)    
    environments+= e._1-> (e._2,m)
    EventDispatcher.listen(e._1,new JobCreatedOnEnvironmentListener(this,moleExecution,e._1),classOf[IEnvironment.JobSubmitted])
  }
  
  
  override def commitHook(hookClassName: String) {
    if (hookPanels.contains(hookClassName)) hookPanels(hookClassName)._2.foreach(_.release)
    hookPanels(hookClassName) =  (hookPanels(hookClassName)._1,hookPanels(hookClassName)._1.saveContent.map(_.coreObject))
  }
  
  def initPieChart = {
    status.keys.foreach(k=>status(k)=new AtomicInteger)
    environments.values.foreach(env=>env._2.keys.foreach(k=> env._2(k) = new AtomicInteger))}
  
    
  class AddHookRowAction(fui: IHookFactoryUI) extends Action(fui.toString){
    def apply = {
      val cl = fui.coreClass.getCanonicalName
      if(hookPanels.contains(cl)) 
        hookPanels(cl)._1.addHook
      else {
        val pui = fui.buildPanelUI(ExecutionManager.this)
        hookPanel.peer.add(pui.peer)
        hookPanel.peer.add((new Separator).peer)
        hookPanels+= cl-> (pui,List.empty)
      }
      hookPanels+= cl-> (hookPanels(cl)._1,hookPanels(cl)._1.saveContent.map(_.coreObject))
    }
  }
  
  class AddGroupingStrategyRowAction(fui: IGroupingStrategyFactoryUI) extends Action(fui.toString){
    def apply = {
      val cl = fui.coreClass.getCanonicalName
      if(gStrategyPanels.contains(cl)) 
        gStrategyPanels(cl)._1.addStrategy
      else {
        val pui = fui.buildPanelUI(ExecutionManager.this)
        hookPanel.peer.add(pui.peer)
        gStrategyPanels+= cl-> (pui,List.empty)
      }
      gStrategyPanels+= cl-> (gStrategyPanels(cl)._1,gStrategyPanels(cl)._1.saveContent.map(_.coreObject))
    }
  }
}
