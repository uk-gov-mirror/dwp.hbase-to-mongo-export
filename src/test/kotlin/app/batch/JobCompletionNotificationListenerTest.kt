package app.batch

import app.exceptions.BlockedTopicException
import app.services.*
import com.nhaarman.mockitokotlin2.*
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import org.junit.Before
import org.junit.Test
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.JobExecution
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.test.util.ReflectionTestUtils

class JobCompletionNotificationListenerTest {

    @Before
    fun before() {
        reset(messagingService)
        reset(snsService)
        reset(pushgatewayService)
        reset(postProcessor)
        reset(runningApplicationsGauge)
        reset(topicsStartedCounter)
        reset(topicsCompletedCounter)
        reset(timer)
    }

    @Test
    fun willIncrementRunningApplicationsCountAndPushMetricsSuccessfully() {
        val exportStatusService = mock<ExportStatusService>()
        val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.EXECUTING
        }
        jobCompletionNotificationListener.beforeJob(jobExecution)
        verifyZeroInteractions(exportStatusService)
        verify(runningApplicationsGauge, times(1)).inc()
        verify(topicsStartedCounter, times(1)).inc()
        verifyNoMoreInteractions(topicsStartedCounter)
        verify(pushgatewayService, times(1)).pushMetrics()
        verifyNoMoreInteractions(pushgatewayService)
        verifyNoMoreInteractions(runningApplicationsGauge)
        verify(durationSummary, times(1)).startTimer()
        verifyNoMoreInteractions(durationSummary)
    }

    @Test
    fun setsExportedStatusOnSuccess() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_SUCCESSFULLY
        }
        val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.COMPLETED
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(exportStatusService, times(1)).setExportedStatus()
        verify(runningApplicationsGauge, times(1)).dec()
        verify(topicsCompletedCounter, times(1)).inc()
        verifyNoMoreInteractions(topicsCompletedCounter)
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(pushgatewayService)
        verifyNoMoreInteractions(runningApplicationsGauge)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }


    @Test
    fun setsFailedStatusOnFailure() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_SUCCESSFULLY
        }

        val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
        ReflectionTestUtils.setField(jobCompletionNotificationListener, "topicName", TEST_TOPIC)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.FAILED
            on { allFailureExceptions } doReturn listOf(Exception(Exception("Failed")))
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(exportStatusService, times(1)).setFailedStatus()
        verify(runningApplicationsGauge, times(1)).dec()
        verify(topicsCompletedCounter, times(1)).inc()
        verifyNoMoreInteractions(topicsCompletedCounter)
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(pushgatewayService)
        verifyNoMoreInteractions(runningApplicationsGauge)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    @Test
    fun setsBlockedTopicStatusOnBlockedTopic() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_SUCCESSFULLY
        }

        val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
        ReflectionTestUtils.setField(jobCompletionNotificationListener, "topicName", TEST_TOPIC)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.FAILED
            on { allFailureExceptions } doReturn listOf(Exception(BlockedTopicException("Blocked")))
        }

        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(exportStatusService, times(1)).setBlockedTopicStatus()
        verify(runningApplicationsGauge, times(1)).dec()
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(pushgatewayService)
        verifyNoMoreInteractions(runningApplicationsGauge)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    @Test
    fun notifiesSnapshotSenderIfNoFilesExported() {
        ExportCompletionStatus.values().forEach { exportCompletionStatus ->
            val exportStatusService = mock<ExportStatusService> {
                on { exportedFilesCount() } doReturn 0
                on { exportCompletionStatus() } doReturn exportCompletionStatus
            }
            val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
            val jobExecution = mock<JobExecution> {
                on { exitStatus } doReturn ExitStatus.COMPLETED
            }
            jobCompletionNotificationListener.afterJob(jobExecution)
            verify(messagingService, times(1)).notifySnapshotSenderNoFilesExported()
            verifyNoMoreInteractions(messagingService)
            verify(pushgatewayService, times(1)).pushFinalMetrics()
            verifyNoMoreInteractions(pushgatewayService)
            reset(messagingService)
            reset(pushgatewayService)
            verify(timer, times(1)).observeDuration()
            verifyNoMoreInteractions(timer)
            reset(timer)
        }
    }

    @Test
    fun doesNotNotifySnapshotSenderIfFilesExported() {
        ExportCompletionStatus.values().forEach { exportCompletionStatus ->
            val exportStatusService = mock<ExportStatusService> {
                on { exportedFilesCount() } doReturn 1
                on { exportCompletionStatus() } doReturn exportCompletionStatus
            }
            val jobCompletionNotificationListener
                = jobCompletionNotificationListener(exportStatusService)
            val jobExecution = mock<JobExecution> {
                on { exitStatus } doReturn ExitStatus.COMPLETED
            }
            jobCompletionNotificationListener.afterJob(jobExecution)
            verifyZeroInteractions(messagingService)
            verify(pushgatewayService, times(1)).pushFinalMetrics()
            verifyNoMoreInteractions(pushgatewayService)
            reset(messagingService)
            reset(pushgatewayService)
            verify(timer, times(1)).observeDuration()
            verifyNoMoreInteractions(timer)
            reset(timer)
        }
    }

    @Test
    fun doesNotNotifySnapshotSenderOnFailure() {
        ExportCompletionStatus.values().forEach { exportCompletionStatus ->
            val exportStatusService = mock<ExportStatusService> {
                on { exportedFilesCount() } doReturn 1
                on { exportCompletionStatus() } doReturn exportCompletionStatus
            }
            val jobCompletionNotificationListener = jobCompletionNotificationListener(exportStatusService)
            ReflectionTestUtils.setField(jobCompletionNotificationListener, "topicName", TEST_TOPIC)
            val jobExecution = mock<JobExecution> {
                on { exitStatus } doReturn ExitStatus.FAILED
            }
            jobCompletionNotificationListener.afterJob(jobExecution)
            verifyZeroInteractions(messagingService)
            verify(pushgatewayService, times(1)).pushFinalMetrics()
            verifyNoMoreInteractions(pushgatewayService)
            reset(messagingService)
            reset(pushgatewayService)
            verify(timer, times(1)).observeDuration()
            verifyNoMoreInteractions(timer)
            reset(timer)
        }
    }

    @Test
    fun sendsExportCompletedSuccessfullyMessageOnSuccess() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_SUCCESSFULLY
        }
        val jobCompletionNotificationListener =
            jobCompletionNotificationListener(exportStatusService)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.COMPLETED
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(snsService, times(1)).sendExportCompletedSuccessfullyMessage()
        verify(snsService, times(1)).sendMonitoringMessage(any())
        verifyNoMoreInteractions(snsService)
        verify(runningApplicationsGauge, times(1)).dec()
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(runningApplicationsGauge)
        verifyNoMoreInteractions(pushgatewayService)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    @Test
    fun doesNotSendExportCompletedSuccessfullyMessageOnSuccessIfNotRequired() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_SUCCESSFULLY
        }
        val jobCompletionNotificationListener =
            jobCompletionNotificationListener(exportStatusService, "false")
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.COMPLETED
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(snsService, times(1)).sendMonitoringMessage(any())
        verifyNoMoreInteractions(snsService)
        verify(runningApplicationsGauge, times(1)).dec()
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(runningApplicationsGauge)
        verifyNoMoreInteractions(pushgatewayService)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    @Test
    fun doesNotSendExportCompletedSuccessfullyMessageOnFailure() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.COMPLETED_UNSUCCESSFULLY
        }
        val jobCompletionNotificationListener =
            jobCompletionNotificationListener(exportStatusService)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.COMPLETED
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verify(snsService, times(1)).sendMonitoringMessage(any())
        verifyNoMoreInteractions(snsService)
        verify(runningApplicationsGauge, times(1)).dec()
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(runningApplicationsGauge)
        verifyNoMoreInteractions(pushgatewayService)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    @Test
    fun doesNotSendAnyMessagesWhileInProgress() {
        val exportStatusService = mock<ExportStatusService> {
            on { exportCompletionStatus() } doReturn ExportCompletionStatus.NOT_COMPLETED
        }
        val jobCompletionNotificationListener =
            jobCompletionNotificationListener(exportStatusService)
        val jobExecution = mock<JobExecution> {
            on { exitStatus } doReturn ExitStatus.COMPLETED
        }
        jobCompletionNotificationListener.afterJob(jobExecution)
        verifyZeroInteractions(snsService)
        verify(runningApplicationsGauge, times(1)).dec()
        verify(pushgatewayService, times(1)).pushFinalMetrics()
        verifyNoMoreInteractions(runningApplicationsGauge)
        verifyNoMoreInteractions(pushgatewayService)
        verify(timer, times(1)).observeDuration()
        verifyNoMoreInteractions(timer)
    }

    private fun jobCompletionNotificationListener(exportStatusService: ExportStatusService,
                                                  triggerAdg: String = "true"): JobCompletionNotificationListener =
        JobCompletionNotificationListener(exportStatusService, messagingService,
            snsService, pushgatewayService, durationSummary, runningApplicationsGauge, topicsStartedCounter, topicsCompletedCounter).apply {
                    ReflectionTestUtils.setField(this, "triggerAdg", triggerAdg)
                }

    private val messagingService = mock<SnapshotSenderMessagingService>()
    private val snsService = mock<SnsService>()
    private val pushgatewayService = mock<PushGatewayService>()
    private val postProcessor = mock<ScheduledAnnotationBeanPostProcessor>()
    private val runningApplicationsGauge = mock<Gauge>()
    private val timer = mock<Summary.Timer>()
    private val durationSummary = mock<Summary> {
        on { startTimer() } doReturn timer
    }
    private val topicsStartedCounter = mock<Counter>()
    private val topicsCompletedCounter = mock<Counter>()
    companion object {
        private const val TEST_TOPIC = "db.test.topic"
    }
}
