package org.arguside.ui.internal.editor

import java.util.HashMap

import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.debug.core.DebugPlugin
import org.eclipse.jdt.core.IClassFile
import org.eclipse.jdt.core.ICompilationUnit
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.IMember
import org.eclipse.jdt.core.IType
import org.eclipse.jdt.core.ITypeRoot
import org.eclipse.jdt.debug.core.JDIDebugModel
import org.eclipse.jdt.internal.debug.ui.BreakpointUtils
import org.eclipse.jdt.internal.debug.ui.DebugWorkingCopyManager
import org.eclipse.jdt.internal.debug.ui.JDIDebugUIPlugin
import org.eclipse.jdt.internal.debug.ui.actions.ActionMessages
import org.eclipse.jdt.internal.debug.ui.actions.ToggleBreakpointAdapter
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.viewers.ISelection
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.jface.viewers.StructuredSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IWorkbenchPart
import org.arguside.core.IArgusPlugin
import org.arguside.core.internal.jdt.model.ArgusSourceTypeElement
import org.arguside.logging.HasLogger
import org.arguside.util.internal.ReflectionUtils

class ArgusToggleBreakpointAdapter extends ToggleBreakpointAdapter with HasLogger { self =>
  import ArgusToggleBreakpointAdapterUtils._

  /** Implementation of the breakpoint toggler. This method relies on the JDT being able
   *  to find the corresponding JDT element for the given selection.
   *
   *  TODO: Rewrite using the presentation compiler, without failing for unknown elements
   *  (unknown to the JDT, such as inner objects inside objects). Breakpoints could be set
   *  by giving only the line number.
   */
  private def toggleLineBreakpointsImpl(part : IWorkbenchPart, selection : ISelection) {
    val job = new Job("Toggle Line Breakpoint") {
      override def run(monitor : IProgressMonitor) : IStatus = {
        val editor = getTextEditor(part)
        if (editor != null && selection.isInstanceOf[ITextSelection]) {
          if (monitor.isCanceled)
            return Status.CANCEL_STATUS
          try {
            report(null, part)
            val sel =
              if(!selection.isInstanceOf[IStructuredSelection])
                translateToMembers(part, selection)
              else
                selection

              if(sel.isInstanceOf[IStructuredSelection]) {
                val member = sel.asInstanceOf[IStructuredSelection].getFirstElement.asInstanceOf[IMember]
                val tpe =
                  if(member.getElementType == IJavaElement.TYPE)
                    member.asInstanceOf[IType]
                  else
                    member.getDeclaringType

                logger.info("setting breakpoint on mbr: %s, tpe: %s [%s]".format(member, tpe.getFullyQualifiedName, tpe.getClass))
                val tname = {
                  val qtname = createQualifiedTypeName(self, tpe)
                  val emptyPackagePrefix = "<empty>."
                  if (qtname startsWith emptyPackagePrefix) qtname.substring(emptyPackagePrefix.length) else qtname
                }
                val resource = BreakpointUtils.getBreakpointResource(tpe)
                val lnumber = selection.asInstanceOf[ITextSelection].getStartLine+1
                val existingBreakpoint = JDIDebugModel.lineBreakpointExists(resource, tname, lnumber)
                if (existingBreakpoint != null) {
                  DebugPlugin.getDefault().getBreakpointManager.removeBreakpoint(existingBreakpoint, true)
                  return Status.OK_STATUS
                }
                val attributes = new HashMap[String, AnyRef](10)
                val documentProvider = editor.getDocumentProvider
                if (documentProvider == null)
                  return Status.CANCEL_STATUS
                val document = documentProvider.getDocument(editor.getEditorInput)
                try {
                  val line = document.getLineInformation(lnumber-1)
                  val start = line.getOffset
                  val end = start+line.getLength-1
                  BreakpointUtils.addJavaBreakpointAttributesWithMemberDetails(attributes, tpe, start, end)
                } catch {
                  case ble : BadLocationException => JDIDebugUIPlugin.log(ble)
                }
                JDIDebugModel.createLineBreakpoint(resource, tname, lnumber, -1, -1, 0, true, attributes)
              } else {
                report(ActionMessages.ToggleBreakpointAdapter_3, part)
                return Status.OK_STATUS
              }
            } catch {
              case ce : CoreException => return ce.getStatus
            }
          }
          return Status.OK_STATUS
        }
      }

    job.setSystem(true)
    job.schedule
  }

  /** Toggle a breakpoint for the given selection. This method relies on the JDT
   *  being able to find the Java Element corresponding to this selection.
   *
   *  TODO: Rewrite to use the presentation compiler for finding the position.
   */
  override def toggleBreakpoints(part : IWorkbenchPart, selection : ISelection) {
    val sel = translateToMembers(part, selection)

    sel match {
      case structuredSelection: IStructuredSelection =>
        val member = structuredSelection.getFirstElement.asInstanceOf[IMember]
        member.getElementType match {
          case IJavaElement.FIELD | IJavaElement.METHOD =>
            selection match {
              case textSelection: ITextSelection => toggleLineBreakpointsImpl(part, textSelection)
              case _                             => ()
            }
          case IJavaElement.TYPE =>
            toggleClassBreakpoints(part, sel)
          case _ =>
            toggleLineBreakpointsImpl(part, selection)
        }
      case _ =>
        logger.info("Unknown selection when toggling breakpoint: " + selection)
    }
  }

  override def toggleLineBreakpoints(part : IWorkbenchPart, selection : ISelection) {
    toggleLineBreakpointsImpl(part, selection)
  }

  /**
   * The implementation of this method is copied from the super class, which had
   * to be overwritten because it doesn't know how to get a ArgusCompilationUnit.
   */
  override def translateToMembers(part: IWorkbenchPart, selection: ISelection): ISelection = {
    def typeRoot(input: IEditorInput): Option[ITypeRoot] =
      Option(input.getAdapter(classOf[IClassFile]).asInstanceOf[IClassFile])
        .orElse(IArgusPlugin().argusCompilationUnit(input).asInstanceOf[Option[ICompilationUnit]])
        .orElse(Option(DebugWorkingCopyManager.getWorkingCopy(input, false)))

    val editor = getTextEditor(part)
    selection match {
      case ts: ITextSelection if editor != null =>
        val input = editor.getEditorInput()
        val provider = editor.getDocumentProvider()
        if (provider == null)
          throw new CoreException(Status.CANCEL_STATUS)

        val offset = {
          val document = provider.getDocument(input)
          if (document == null)
            ts.getOffset()
          else {
            var o = ts.getOffset()
            val r = document.getLineInformationOfOffset(o)
            val end = r.getOffset()+r.getLength()
            while (o < end && Character.isWhitespace(document.getChar(o)))
              o += 1
            o
          }
        }

        val root = typeRoot(input)
        root match {
          case Some(cu: ICompilationUnit) =>
            cu.synchronized {
              cu.reconcile(ICompilationUnit.NO_AST, false, null, null)
            }
          case _ =>
        }

        root.map(_.getElementAt(offset)) match {
          case Some(m: IMember) => new StructuredSelection(m)
          case _ => selection
        }

      case _ =>
        selection
    }
  }
}

object ArgusToggleBreakpointAdapterUtils extends ReflectionUtils {
  val toggleBreakpointAdapterClazz = classOf[ToggleBreakpointAdapter]
  val createQualifiedTypeNameMethod = getDeclaredMethod(toggleBreakpointAdapterClazz, "createQualifiedTypeName", classOf[IType])

  def createQualifiedTypeName(tba : ToggleBreakpointAdapter, tpe : IType) = {
    if (tpe.isInstanceOf[ArgusSourceTypeElement])
      tpe.getFullyQualifiedName
    else
      createQualifiedTypeNameMethod.invoke(tba, tpe).asInstanceOf[String]
  }
}
