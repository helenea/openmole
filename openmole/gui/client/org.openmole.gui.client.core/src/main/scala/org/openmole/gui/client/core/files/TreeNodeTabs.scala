package org.openmole.gui.client.core.files

import org.openmole.gui.ext.data._

import scala.concurrent.ExecutionContext.Implicits.global
import boopickle.Default._
import autowire._
import org.openmole.gui.ext.tool.client.Utils._

import scala.concurrent.duration._
import scaladget.bootstrapnative.bsn._
import scaladget.tools._
import org.openmole.gui.ext.api.Api
import org.scalajs.dom.raw.HTMLElement
import rx._
import scalatags.JsDom.all.{ raw, _ }
import scalatags.JsDom.TypedTag
import org.openmole.gui.ext.tool.client._
import org.openmole.gui.client.core._
import org.openmole.gui.ext.tool.client.FileManager
import DataUtils._
import com.definitelyscala.plotlyjs.PlotType
import net.scalapro.sortable._
import org.openmole.gui.client.core.files.TreeNodeTab.{ EditableView, RowFilter }
import org.openmole.gui.client.tool.plot
import org.openmole.gui.client.tool.plot.Plot.{ PlotMode, ScatterMode, XYMode }
import org.openmole.gui.client.tool.plot._
import org.openmole.gui.client.tool.plot.Tools._
import scaladget.bootstrapnative.DataTable

import scala.scalajs.js.timers._

object TreeNodeTabs {

  sealed trait Activity

  object Active extends Activity

  object UnActive extends Activity

}

import TreeNodeTabs._

sealed trait TreeNodeTab {

  val safePathTab: Var[SafePath]
  val activity: Var[Activity] = Var(UnActive)

  val tabName = Var(safePathTab.now.name)
  val id: String = getUUID

  def activate = activity() = Active

  def desactivate = activity() = UnActive

  def extension: FileExtension = safePathTab.now.name

  // Get the file content to be saved
  def content: String

  def editable: Boolean

  def editing: Boolean

  def refresh(afterRefresh: () ⇒ Unit = () ⇒ {}): Unit

  def resizeEditor: Unit

  // controller to be added in menu bar
  val controlElement: TypedTag[HTMLElement]

  // Graphical representation
  val block: TypedTag[_ <: HTMLElement]
}

object TreeNodeTab {

  implicit val ctx: Ctx.Owner = Ctx.Owner.safe()

  def save(safePath: SafePath, editorPanelUI: EditorPanelUI, afterSave: () ⇒ Unit) =
    editorPanelUI.synchronized {
      post()[Api].saveFile(safePath, editorPanelUI.code).call().foreach(_ ⇒ afterSave())
    }

  def oms(safePath: SafePath, initialContent: String) = new TreeNodeTab {

    lazy val safePathTab = Var(safePath)

    val editor = EditorPanelUI(FileExtension.OMS, initialContent)
    editor.initEditor

    def editable = true

    def editing = true

    def content = editor.code

    def refresh(onsaved: () ⇒ Unit) = save(safePathTab.now, editor, onsaved)

    def resizeEditor = editor.editor.resize()

    lazy val controlElement = button("Run", btn_primary, onclick := { () ⇒
      refresh(() ⇒
        post(timeout = 120 seconds, warningTimeout = 60 seconds)[Api].runScript(ScriptData(safePathTab.now)).call().foreach { execInfo ⇒
          org.openmole.gui.client.core.panels.executionPanel.dialog.show
        })
    })

    lazy val block = editor.view
  }

  def html(safePath: SafePath, htmlContent: String) = new TreeNodeTab {
    lazy val safePathTab = Var(safePath)

    def content: String = htmlContent

    def editable: Boolean = false

    def editing: Boolean = false

    def refresh(afterRefresh: () ⇒ Unit): Unit = () ⇒ {}

    def resizeEditor = {}

    lazy val controlElement: TypedTag[HTMLElement] = div()

    lazy val block: TypedTag[_ <: HTMLElement] = div(editorContainer +++ container)(
      div(panelClass +++ panelDefault)(
        div(panelBody)(
          ms("mdRendering") +++ (padding := 10),
          RawFrag(htmlContent)
        )
      )
    )
  }

  sealed trait EditableView {
    toString: String
  }

  object Raw extends EditableView {
    override def toString = "Raw"
  }

  object Table extends EditableView {
    override def toString = "Table"
  }

  object Plot extends EditableView {
    override def toString = "Plot"
  }

  sealed trait RowFilter

  object First100 extends RowFilter

  object Last100 extends RowFilter

  object All extends RowFilter

  def editable(
    safePath:        SafePath,
    initialContent:  String,
    initialSequence: SequenceData,
    view:            EditableView = Raw,
    initialEditing:  Boolean      = false,
    filter:          RowFilter    = First100,
    axis:            (Int, Int)   = (0, 1),
    plotMode:        PlotMode     = ScatterMode): TreeNodeTab = new TreeNodeTab {

    lazy val safePathTab = Var(safePath)
    lazy val isEditing = Var(initialEditing)

    Rx {
      editor.setReadOnly(!isEditing())
    }

    def content: String = editor.code

    val sequence = Var(initialSequence)
    val nbColumns = sequence.now.header.length

    def isCSV = DataUtils.isCSV(safePath)

    val filteredSequence = filter match {
      case First100 ⇒ sequence.now.content.take(100)
      case Last100  ⇒ sequence.now.content.takeRight(100)
      case _        ⇒ sequence.now.content
    }

    lazy val editor = EditorPanelUI(extension, initialContent, if (isCSV) paddingBottom := 80 else emptyMod)
    editor.initEditor

    def editable = true

    def editing = isEditing.now

    def download(afterRefresh: () ⇒ Unit) = editor.synchronized {
      FileManager.download(
        safePathTab.now,
        (p: ProcessState) ⇒ {},
        (cont: String) ⇒ {
          editor.setCode(cont)
          if (isCSV) {
            post()[Api].sequence(safePathTab.now).call().foreach { seq ⇒
              sequence() = seq
              afterRefresh()
            }
          }
          else afterRefresh()
        }
      )
    }

    def refresh(afterRefresh: () ⇒ Unit): Unit = {
      def saveTab = TreeNodeTab.save(safePathTab.now, editor, afterRefresh)

      if (editing) {
        if (isCSV) {
          if (view == Raw) saveTab
        }
        else
          saveTab
      }
      else
        download(afterRefresh)
    }

    def resizeEditor = editor.editor.resize()

    lazy val controlElement: TypedTag[HTMLElement] =
      div(
        Rx {
          if (isEditing()) div()
          else if (view == Raw) {
            button("Edit", btn_primary, onclick := { () ⇒
              isEditing() = !isEditing.now
            })
          }
          else div()
        }
      )

    lazy val editorView = editor.view

    val switchString = view match {
      case Table ⇒ Raw.toString
      case _     ⇒ Table.toString
    }

    def switchView(newView: EditableView) = {

      def switch = panels.treeNodeTabs.switchEditableTo(this, sequence.now, newView, filter, editing, axis, plotMode)

      newView match {
        case Table | Plot ⇒
          isEditing() = false
          switch
        case _ ⇒
          if (editing)
            refresh(() ⇒ {
              download(() ⇒ switch)
            })
          else switch
      }
    }

    def toView(filter: RowFilter) = panels.treeNodeTabs.switchEditableTo(this, sequence.now, view, filter, editing, axis, plotMode)

    def toView(newAxis: (Int, Int)) = panels.treeNodeTabs.switchEditableTo(this, sequence.now, view, filter, editing, newAxis, plotMode)

    def toView(newMode: PlotMode) = panels.treeNodeTabs.switchEditableTo(this, sequence.now, view, filter, editing, axis, newMode)

    lazy val switchButton = radios(margin := 20)(
      selectableButton("Raw", view == Raw, onclick = () ⇒ switchView(Raw)),
      selectableButton("Table", view == Table, onclick = () ⇒ switchView(Table)),
      selectableButton("Plot", view == Plot, onclick = () ⇒ switchView(Plot))
    )

    lazy val filterRadios = radios(marginLeft := 40)(
      selectableButton("First 100", filter == First100, onclick = () ⇒ toView(First100)),
      selectableButton("Last 100", filter == Last100, onclick = () ⇒ toView(Last100)),
      selectableButton("All", filter == All, modifierSeq = btn_danger, onclick = () ⇒ toView(All))
    )

    lazy val axisCheckBoxes = checkboxes(margin := 20)(
      (for (
        a ← sequence.now.header.zipWithIndex
      ) yield {
        selectableButton(a._1, axis._1 == a._2 || axis._2 == a._2, onclick = () ⇒ toView((axis._2, a._2)))
      }): _*
    )

    lazy val plotModeRadios = radios(marginLeft := 40)(
      selectableButton("Line", plotMode == XYMode, onclick = () ⇒ toView(XYMode)),
      selectableButton("Scatter", plotMode == ScatterMode, onclick = () ⇒ toView(ScatterMode))
    )

    lazy val block: TypedTag[_ <: HTMLElement] = {
      div(
        if (isCSV) {
          view match {
            case Table ⇒ div(switchButton.render, filterRadios.render)
            case Plot ⇒
              div(
                div(switchButton.render, filterRadios.render, plotModeRadios.render),
                axisCheckBoxes.render
              )
            case _ ⇒ div(switchButton.render, div.render)
          }
        }
        else div,
        view match {
          case Table ⇒
            div(overflow := "auto", height := "90%")(
              {
                if (!sequence.now.header.isEmpty && !filteredSequence.isEmpty) {
                  val table =
                    scaladget.bootstrapnative.DataTable(
                      Some(scaladget.bootstrapnative.Table.Header(sequence.now.header)),
                      filteredSequence.map {
                        scaladget.bootstrapnative.DataTable.DataRow(_)
                      }.toSeq,
                      scaladget.bootstrapnative.Table.BSTableStyle(bordered_table, emptyMod), true)
                  table.render(width := sequence.now.header.length * 90)
                }
                else div()
              }
            )
          case Raw ⇒ editorView
          case _ ⇒
            if (filteredSequence.size > 0) {
              if (filteredSequence.head.length > Math.max(axis._1, axis._2)) {
                val dataRow = filteredSequence.map {
                  scaladget.bootstrapnative.DataTable.DataRow(_)
                }.toSeq
                val col1 = DataTable.column(axis._1, dataRow)
                val col2 = DataTable.column(axis._2, dataRow)
                val xyplot = plot.Plot(
                  "",
                  sequence.now.header(axis._1),
                  sequence.now.header(axis._2),
                  Seq(Serie(
                    x = col1.values.toArray,
                    y = col2.values.toArray
                  )),
                  false,
                  plotMode
                )
                xyplot
              }
              else div("No plot to display")
            }
            else div("No plot to display")
        }
      )
    }

  }
}

class TreeNodeTabs() {

  implicit val ctx: Ctx.Owner = Ctx.Owner.safe()

  val tabs: Var[Seq[TreeNodeTab]] = Var(Seq())
  val timer: Var[Option[SetIntervalHandle]] = Var(None)
  val temporaryControl: Var[TypedTag[HTMLElement]] = Var(div())

  def stopTimerIfNoTabs = {
    if (tabs.now.isEmpty) {
      timer.map {
        _.foreach {
          clearInterval
        }
      }
      timer() = None
    }
  }

  def startTimerIfStopped =
    timer.now match {
      case None ⇒
        timer() = Some(setInterval(15000) {
          tabs.now.foreach {
            _.refresh()
          }
        })
      case _ ⇒
    }

  def setActive(tab: TreeNodeTab) = {
    if (tabs.now.contains(tab)) {
      unActiveAll
    }
    tab.activate
  }

  def unActiveAll = tabs.map {
    _.foreach { t ⇒
      t.desactivate
    }
  }

  def ++(tab: TreeNodeTab) = {
    tabs() = tabs.now :+ tab
    startTimerIfStopped
    setActive(tab)
  }

  def removeTab(tab: TreeNodeTab) = {
    tab.desactivate
    val newTabs = tabs.now.filterNot {
      _ == tab
    }
    tabs() = newTabs
    if (tabs.now.isEmpty) temporaryControl() = div()
    newTabs.lastOption.map { t ⇒
      setActive(t)
    }
  }

  def --(tab: TreeNodeTab): Unit = tab.refresh(() ⇒ removeTab(tab))

  def --(safePath: SafePath): Unit = {
    find(safePath).map {
      removeTab
    }
  }

  def switchEditableTo(tab: TreeNodeTab, sequence: SequenceData, editableView: EditableView, filter: RowFilter, editing: Boolean, axis: (Int, Int), plotMode: PlotMode) = {
    val index = {
      val i = tabs.now.indexOf(tab)
      if (i == -1) tabs.now.size
      else i
    }

    removeTab(tab)

    val newTab = TreeNodeTab.editable(tab.safePathTab.now, tab.content, sequence, editableView, editing, filter, axis, plotMode)
    tabs() = tabs.now.take(index) ++ Seq(newTab) ++ tabs.now.takeRight(tabs.now.size - index)

    setActive(newTab)
  }

  def alterables: Seq[AlterableFileContent] = tabs.now.filter {
    _.editable
  }.map { t ⇒ AlterableFileContent(t.safePathTab.now, t.content) }

  def saveAllTabs(onsave: () ⇒ Unit) = {
    org.openmole.gui.client.core.post()[Api].saveFiles(alterables).call().foreach { s ⇒
      onsave()
    }
  }

  def checkTabs = tabs.now.foreach { t: TreeNodeTab ⇒
    org.openmole.gui.client.core.post()[Api].exists(t.safePathTab.now).call().foreach { e ⇒
      if (!e) removeTab(t)
    }
  }

  def rename(sp: SafePath, newSafePath: SafePath) = {
    find(sp).map { tab ⇒
      tab.tabName() = newSafePath.name
      tab.safePathTab() = newSafePath
    }
  }

  def find(safePath: SafePath) = tabs.now.find { t ⇒
    t.safePathTab.now == safePath
  }

  implicit def modToModSeq(m: Modifier): ModifierSeq = Seq(m)

  val render = div(
    //Headers
    Rx {
      val tabList = ul(nav +++ navTabs, tab_list_role)(
        for (t ← tabs()) yield {
          li(
            paddingTop := 35,
            presentation_role,
            `class` := {
              t.activity() match {
                case Active ⇒ "active"
                case _      ⇒ ""
              }
            }
          )(
              a(
                id := t.id,
                tab_role,
                pointer,
                t.activity() match {
                  case Active ⇒ activeTab
                  case _      ⇒ unActiveTab
                },
                data("toggle") := "tab", onclick := { () ⇒
                  setActive(t)
                }
              )(
                  button(ms("close") +++ tabClose, `type` := "button", onclick := { () ⇒ --(t) })(raw("&#215")),
                  t.tabName()
                )
            )
        }
      ).render

      //Panes
      val tabDiv = div(tabContent)(
        for (t ← tabs()) yield {
          div(
            role := "tabpanel",
            ms("tab-pane " + {
              t.activity() match {
                case Active ⇒ "active"
                case _      ⇒ ""
              }
            }), id := t.id
          )({
              t.activity() match {
                case Active ⇒
                  temporaryControl() = t.controlElement
                  t.block
                case UnActive ⇒ div()
              }
            }
            )
        }
      )

      new Sortable(tabList, new SortableProps {
        override val onEnd = scala.scalajs.js.defined {
          (event: EventS) ⇒
            val oldI = event.oldIndex.asInstanceOf[Int]
            val newI = event.newIndex.asInstanceOf[Int]
            tabs() = tabs.now.updated(oldI, tabs.now(newI)).updated(newI, tabs.now(oldI))
            setActive(tabs.now(newI))
        }
      })

      div(role := "tabpanel")(
        tabList,
        tabDiv
      )
    }
  )

}