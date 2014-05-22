/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Association du Paris Java User Group.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package library


import akka.actor._
import play.api.Play.current
import scala.Predef._
import models._
import org.joda.time._
import play.libs.Akka
import play.api.Play
import notifiers.Mails
import play.api.libs.json.Json
import java.io.File
import org.apache.commons.io.FileUtils
import scala.collection.JavaConverters._
import org.apache.commons.io.filefilter.{SuffixFileFilter, WildcardFileFilter}
import library.wordpress.WordpressPublisher

/**
 * Akka actor that is in charge to process batch operations and long running queries
 *
 * Author: nicolas martignole
 * Created: 07/11/2013 16:20
 */

// This is a simple Akka event
case class ReportIssue(issue: Issue)

case class SendMessageToSpeaker(reporterUUID: String, proposal: Proposal, msg: String)

case class SendMessageToCommitte(reporterUUID: String, proposal: Proposal, msg: String)

case class SendMessageInternal(reporterUUID: String, proposal: Proposal, msg: String)

case class DraftReminder()

case class ComputeLeaderboard()

case class ComputeVotesAndScore()

case class RemoveVotesForDeletedProposal()

case class ProposalApproved(reporterUUID: String, proposal: Proposal)

case class ProposalRejected(reporterUUID: String, proposal: Proposal)

case class SaveSlots(confType: String, slots: List[Slot], createdBy: Webuser)

case class LogURL(url: String, objRef: String, objValue: String)

case class ProcessCSVFile(fileName: String)

case class ProcessCSVDir(dir: String)

case class NotifySpeakerRequestToTalk(authorUUiD: String, rtt: RequestToTalk)

case class EditRequestToTalk(authorUUiD: String, rtt: RequestToTalk)



// Defines an actor (no failover strategy here)
object ZapActor {
  val actor = Akka.system.actorOf(Props[ZapActor])
}

class ZapActor extends Actor {
  def receive = {
    case ReportIssue(issue) => publishBugReport(issue)
    case SendMessageToSpeaker(reporterUUID, proposal, msg) => sendMessageToSpeaker(reporterUUID, proposal, msg)
    case SendMessageToCommitte(reporterUUID, proposal, msg) => sendMessageToCommitte(reporterUUID, proposal, msg)
    case SendMessageInternal(reporterUUID, proposal, msg) => postInternalMessage(reporterUUID, proposal, msg)
    case DraftReminder() => sendDraftReminder()
    case ComputeLeaderboard() => doComputeLeaderboard()
    case ComputeVotesAndScore() => doComputeVotesAndScore()
    case RemoveVotesForDeletedProposal() => doRemoveVotesForDeletedProposal()
    case ProposalApproved(reporterUUID, proposal) => doProposalApproved(reporterUUID, proposal)
    case ProposalRejected(reporterUUID, proposal) => doProposalRejected(reporterUUID, proposal)
    case SaveSlots(confType: String, slots: List[Slot], createdBy: Webuser) => doSaveSlots(confType: String, slots: List[Slot], createdBy: Webuser)
    case LogURL(url: String, objRef: String, objValue: String) => doLogURL(url: String, objRef: String, objValue: String)
    case ProcessCSVFile(fileName: String) => doProcessCSV(fileName)
    case ProcessCSVDir(dir: String) => doProcessCSVDir(dir)
    case NotifySpeakerRequestToTalk(authorUUiD: String, rtt: RequestToTalk) => doNotifySpeakerRequestToTalk(authorUUiD, rtt)
    case EditRequestToTalk(authorUUiD: String, rtt: RequestToTalk) => doEditRequestToTalk(authorUUiD, rtt)
    case other => play.Logger.of("application.ZapActor").error("Received an invalid actor message: " + other)
  }

  def publishBugReport(issue: Issue) {
    if (play.Logger.of("application.ZapActor").isDebugEnabled) {
      play.Logger.of("application.ZapActor").debug(s"Posting a new bug report to Bitbucket")
    }

    // All the functional code should be outside the Actor, so that we can test it separately
    Issue.publish(issue)
  }

  def sendMessageToSpeaker(reporterUUID: String, proposal: Proposal, msg: String) {

    for (reporter <- Webuser.findByUUID(reporterUUID);
         speaker <- Webuser.findByUUID(proposal.mainSpeaker)) yield {
      Event.storeEvent(Event(proposal.id, reporterUUID, s"Sending a message to ${speaker.cleanName} about ${proposal.title}"))
      Mails.sendMessageToSpeakers(reporter, speaker, proposal, msg)
    }
  }

  def sendMessageToCommitte(reporterUUID: String, proposal: Proposal, msg: String) {
    Event.storeEvent(Event(proposal.id, reporterUUID, s"Sending a message to committee about ${proposal.id} ${proposal.title}"))
    Webuser.findByUUID(reporterUUID).map {
      reporterWebuser: Webuser =>
        Mails.sendMessageToCommitte(reporterWebuser, proposal, msg)
    }.getOrElse {
      play.Logger.error("User not found with uuid " + reporterUUID)
    }
  }

  def postInternalMessage(reporterUUID: String, proposal: Proposal, msg: String) {
    Event.storeEvent(Event(proposal.id, reporterUUID, s"Posted an internal message fpr ${proposal.id} ${proposal.title}"))
    Webuser.findByUUID(reporterUUID).map {
      reporterWebuser: Webuser =>
        Mails.postInternalMessage(reporterWebuser, proposal, msg)
    }.getOrElse {
      play.Logger.error("User not found with uuid " + reporterUUID)
    }
  }

  def sendDraftReminder() {
    val allProposalBySpeaker = Proposal.allDrafts().groupBy(_.mainSpeaker)
    allProposalBySpeaker.foreach {
      case (speaker: String, draftProposals: List[Proposal]) => {
        Webuser.findByUUID(speaker).map {
          speakerUser =>
            Mails.sendReminderForDraft(speakerUser, draftProposals)
        }.getOrElse {
          play.Logger.warn("User not found")
        }
      }
    }
  }

  def doComputeLeaderboard() {
    Leaderboard.computeStats()
  }

  def doComputeVotesAndScore() {
    Review.computeAndGenerateVotes()
  }

  // Delete votes if a proposal was deleted
  def doRemoveVotesForDeletedProposal() {
    Proposal.allProposalIDsDeleted.map {
      proposalId =>
        Review.deleteVoteForProposal(proposalId)
    }
  }

  def doProposalApproved(reporterUUID: String, proposal: Proposal) {
    for (reporter <- Webuser.findByUUID(reporterUUID);
         speaker <- Webuser.findByUUID(proposal.mainSpeaker)) yield {
      Event.storeEvent(Event(proposal.id, reporterUUID, s"Sent proposal Approved"))
      Mails.sendProposalApproved(speaker, proposal)
      Proposal.approve(reporterUUID, proposal.id)
    }
  }

  def doProposalRejected(reporterUUID: String, proposal: Proposal) {
    for (reporter <- Webuser.findByUUID(reporterUUID);
         speaker <- Webuser.findByUUID(proposal.mainSpeaker)) yield {
      Event.storeEvent(Event(proposal.id, reporterUUID, s"Sent proposal Refused"))
      Mails.sendProposalRefused(speaker, proposal)
      Proposal.reject(reporterUUID, proposal.id)
    }
  }

  def doSaveSlots(confType: String, slots: List[Slot], createdBy: Webuser) {
    ScheduleConfiguration.persist(confType, slots, createdBy)
  }

  def doLogURL(url: String, objRef: String, objValue: String) {
    HitView.storeLogURL(url, objRef, objValue)
  }

  def doProcessCSV(fileName: String) {
    play.Logger.info("Processing csv file " + fileName)
    library.csv.PDFBadgeGenerator.handle(fileName)
    //library.csv.QRCodeGenerator.handle(fileName)
  }

  val csvBadgesFolderFilter: java.io.FilenameFilter = new WildcardFileFilter("*-badges")
  val csvFiles: java.io.FilenameFilter = new SuffixFileFilter(".csv")

  def doProcessCSVDir(dir: String) {

    val directory: java.io.File = new File(dir)

    if (directory.exists() && directory.canRead) {
      play.Logger.info("Processing dir [" + directory.getAbsolutePath + "]")
      val badgesFolders = directory.listFiles(csvBadgesFolderFilter)

      badgesFolders.foreach {
        d =>
          d.listFiles(csvFiles).foreach {
            csvFile: File =>
              ZapActor.actor ! doProcessCSV(csvFile.getAbsolutePath)
          }
      }
    } else {
      play.Logger.error("Cannot read or parse directory " + dir)
    }
  }

  def doNotifySpeakerRequestToTalk(authorUUID: String, rtt: RequestToTalk) {
    RequestToTalk.save(authorUUID, rtt)
    Mails.sendInvitationForSpeaker(rtt.speakerEmail, rtt.note, rtt.id)
  }

  def doEditRequestToTalk(authorUUID: String, rtt: RequestToTalk) {
    RequestToTalk.save(authorUUID, rtt)
  }
}
