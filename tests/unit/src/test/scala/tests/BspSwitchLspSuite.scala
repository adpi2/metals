package tests

import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands

import bill.Bill

class BspSwitchLspSuite extends BaseQuickBuildSuite("bsp-switch") {

  test("switch".flaky) {
    cleanWorkspace()
    Bill.installWorkspace(workspace.toNIO)
    for {
      _ <- initialize("")
      _ = {
        client.messageRequests.clear()
        assertConnectedToBuildServer("Bill")
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch.id)
      _ = {
        assertConnectedToBuildServer("Bill")
        assertNoDiff(
          client.workspaceShowMessages,
          BspSwitch.onlyOneServer("Bill").getMessage()
        )
      }
      _ = {
        client.messageRequests.clear()
        assertConnectedToBuildServer("Bill")
        Bill.installWorkspace(workspace.toNIO, "Bob")
      }
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer.id)
      _ = {
        assertConnectedToBuildServer("Bob")
        assertNoDiff(
          client.workspaceMessageRequests,
          SelectBspServer.message
        )
        assertNoDiff(client.workspaceShowMessages, "")

        client.messageRequests.clear()
        client.showMessageRequestHandler = { params =>
          params.getActions.asScala.find(_.getTitle == "Bill")
        }
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch.id)
      _ = {
        assertNoDiff(client.workspaceShowMessages, "")
        assertConnectedToBuildServer("Bill")
      }
    } yield ()
  }
}
