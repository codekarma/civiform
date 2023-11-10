package repository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.ebean.DB;
import io.ebean.Database;
import io.ebean.ExpressionList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.inject.Inject;
import models.Applicant;
import models.Application;
import models.LifecycleStage;
import models.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import services.applicant.exception.ApplicantNotFoundException;
import services.applicant.exception.DuplicateApplicationException;
import services.program.ProgramNotFoundException;

/**
 * ApplicationRepository performs complicated operations on {@link Application} that often involve
 * other EBean models or asynchronous handling.
 */
public final class ApplicationRepository {
  private final QueryProfileLocationBuilder queryProfileLocationBuilder =
      new QueryProfileLocationBuilder("ApplicationRepository");

  private final ProgramRepository programRepository;
  private final AccountRepository accountRepository;
  private final Database database;
  private final DatabaseExecutionContext executionContext;
  private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationRepository.class);

  @Inject
  public ApplicationRepository(
      ProgramRepository programRepository,
      AccountRepository accountRepository,
      DatabaseExecutionContext executionContext) {
    this.programRepository = checkNotNull(programRepository);
    this.accountRepository = checkNotNull(accountRepository);
    this.database = DB.getDefault();
    this.executionContext = checkNotNull(executionContext);
  }

  @VisibleForTesting
  public CompletionStage<Application> submitApplication(
      Applicant applicant, Program program, Optional<String> tiSubmitterEmail) {
    return supplyAsync(
        () -> submitApplicationInternal(applicant, program, tiSubmitterEmail),
        executionContext.current());
  }

  /**
   * Submit an application, which will delete any in-progress drafts, obsolete any submitted
   * applications to a program with the same name (to include past versions of the same program),
   * and create a new application in the active state.
   */
  public CompletionStage<Optional<Application>> submitApplication(
      long applicantId, long programId, Optional<String> tiSubmitterEmail) {
    return this.perform(
        applicantId,
        programId,
        (ApplicationArguments appArgs) ->
            submitApplicationInternal(appArgs.applicant, appArgs.program, tiSubmitterEmail));
  }

  private Application submitApplicationInternal(
      Applicant applicant, Program program, Optional<String> tiSubmitterEmail) {
    database.beginTransaction();
    try {
      List<Application> oldApplications =
          database
              .createQuery(Application.class)
              .where()
              .eq("applicant.id", applicant.id)
              .eq("program.name", program.getProgramDefinition().adminName())
              .setLabel("Application.findList")
              .setProfileLocation(queryProfileLocationBuilder.create("submitApplicationInternal"))
              .findList();

      ImmutableList<Application> drafts =
          oldApplications.stream()
              .filter(app -> app.getLifecycleStage().equals(LifecycleStage.DRAFT))
              .collect(ImmutableList.toImmutableList());

      final Application application;
      if (drafts.size() == 1) {
        application = drafts.get(0);
      } else if (drafts.isEmpty()) {
        LOGGER.warn(
            "No DRAFT applications found when submitting for applicant {} program {}",
            applicant.id,
            program.id);
        application = new Application(applicant, program, LifecycleStage.ACTIVE);
      } else {
        throw new RuntimeException(
            String.format(
                "Found more than one DRAFT application for applicant %d, program %d.",
                applicant.id, program.id));
      }

      ImmutableList<Application> previousActive =
          oldApplications.stream()
              .filter(app -> app.getLifecycleStage().equals(LifecycleStage.ACTIVE))
              .collect(ImmutableList.toImmutableList());

      if (previousActive.size() > 1) {
        // This shouldn't really be possible, but just in case
        LOGGER.warn(
            "Multiple previous active applications found for applicant {} to program {} {}. All"
                + " will be set to OBSOLETE. Application IDs: {}",
            applicant.id,
            program.id,
            program.getProgramDefinition().adminName(),
            String.join(
                ",",
                previousActive.stream()
                    .map(app -> app.id.toString())
                    .collect(ImmutableList.toImmutableList())));
      }

      for (Application app : previousActive) {
        boolean isDuplicate = applicant.getApplicantData().isDuplicateOf(app.getApplicantData());
        if (isDuplicate) {
          LOGGER.info(
              "Application for applicant {} to program {} {} was detected as a duplicate and was"
                  + " not saved",
              applicant.id,
              program.id,
              program.getProgramDefinition().adminName());
          throw new DuplicateApplicationException();
        }
        // https://github.com/civiform/civiform/issues/3227
        if (app.getSubmitTime() == null) {
          app.setSubmitTimeToNow();
        }
        app.setLifecycleStage(LifecycleStage.OBSOLETE);
        app.save();
      }

      application.setApplicantData(applicant.getApplicantData());
      application.setLifecycleStage(LifecycleStage.ACTIVE);
      application.setSubmitTimeToNow();
      if (tiSubmitterEmail.isPresent()) {
        application.setSubmitterEmail(tiSubmitterEmail.get());
      }
      application.save();

      database.commitTransaction();
      return application;
    } finally {
      database.endTransaction();
    }
  }

  /**
   * Retrieves an applicant and program record and executes the provided function with them with
   * some error handling.
   */
  private CompletionStage<Optional<Application>> perform(
      long applicantId, long programId, Function<ApplicationArguments, Application> fn) {
    CompletionStage<Optional<Applicant>> applicantDb =
        accountRepository.lookupApplicant(applicantId);
    CompletionStage<Optional<Program>> programDb = programRepository.lookupProgram(programId);
    return applicantDb
        .thenCombineAsync(
            programDb,
            (applicantMaybe, programMaybe) -> {
              if (applicantMaybe.isEmpty()) {
                throw new RuntimeException(new ApplicantNotFoundException(applicantId));
              }
              if (programMaybe.isEmpty()) {
                throw new RuntimeException(new ProgramNotFoundException(programId));
              }
              return new ApplicationArguments(programMaybe.get(), applicantMaybe.get());
            })
        .thenApplyAsync(fn)
        .thenApplyAsync(Optional::of)
        .exceptionally(
            exception -> {
              if (exception.getCause() instanceof DuplicateApplicationException) {
                throw new DuplicateApplicationException();
              }
              LOGGER.error(exception.toString());
              exception.printStackTrace();
              return Optional.empty();
            });
  }

  /**
   * Returns all applications submitted within the provided time range. Results are returned in the
   * order that the applications were created.
   */
  public ImmutableList<Application> getApplications(TimeFilter submitTimeFilter) {
    ExpressionList<Application> query =
        database
            .find(Application.class)
            .fetch("program")
            .fetch("applicant.account")
            .orderBy("id")
            .where();
    if (submitTimeFilter.fromTime().isPresent()) {
      query = query.where().ge("submit_time", submitTimeFilter.fromTime().get());
    }
    if (submitTimeFilter.untilTime().isPresent()) {
      query = query.where().lt("submit_time", submitTimeFilter.untilTime().get());
    }
    return ImmutableList.copyOf(
        query
            .setLabel("Application.findList")
            .setProfileLocation(queryProfileLocationBuilder.create("getApplications"))
            .findList());
  }

  // Need to transmit both arguments to submitApplication through the CompletionStage pipeline.
  // Not useful in the API, not needed more broadly.
  private static final class ApplicationArguments {
    public Program program;
    public Applicant applicant;

    public ApplicationArguments(Program program, Applicant applicant) {
      this.program = program;
      this.applicant = applicant;
    }
  }

  private Application createOrUpdateDraftApplicationInternal(Applicant applicant, Program program) {
    database.beginTransaction();
    try {
      Optional<Application> existingDraft =
          database
              .createQuery(Application.class)
              .where()
              .eq("applicant.id", applicant.id)
              .eq("program.name", program.getProgramDefinition().adminName())
              .eq("lifecycle_stage", LifecycleStage.DRAFT)
              .setLabel("Application.findById")
              .setProfileLocation(
                  queryProfileLocationBuilder.create("createOrUpdateDraftApplicationInternal"))
              .findOneOrEmpty();
      Application application =
          existingDraft.orElseGet(() -> new Application(applicant, program, LifecycleStage.DRAFT));
      application.save();
      database.commitTransaction();
      return application;
    } finally {
      database.endTransaction();
    }
  }

  @VisibleForTesting
  CompletionStage<Application> createOrUpdateDraft(Applicant applicant, Program program) {
    return supplyAsync(
        () -> createOrUpdateDraftApplicationInternal(applicant, program),
        executionContext.current());
  }

  /**
   * Create a draft application for the specified program. Update the draft application if one
   * already exists.
   */
  public CompletionStage<Optional<Application>> createOrUpdateDraft(
      long applicantId, long programId) {
    return this.perform(
        applicantId,
        programId,
        (ApplicationArguments appArgs) ->
            createOrUpdateDraftApplicationInternal(appArgs.applicant, appArgs.program));
  }

  public CompletionStage<Optional<Application>> getApplication(long applicationId) {
    return supplyAsync(
        () ->
            database
                .find(Application.class)
                .setId(applicationId)
                .setLabel("Application.findById")
                .setProfileLocation(queryProfileLocationBuilder.create("getApplication"))
                .findOneOrEmpty(),
        executionContext.current());
  }

  /**
   * Get all applications with the specified {@link LifecycleStage}s for an applicant.
   *
   * <p>The {@link Program} associated with the application is eagerly loaded.
   */
  public CompletionStage<ImmutableSet<Application>> getApplicationsForApplicant(
      long applicantId, ImmutableSet<LifecycleStage> stages) {
    return supplyAsync(
        () -> {
          return database
              .find(Application.class)
              .where()
              .eq("applicant.id", applicantId)
              .isIn("lifecycle_stage", stages)
              .query()
              // Eagerly fetch the program in a SQL join.
              .fetch("program")
              .fetch("applicationEvents")
              .setLabel("Application.findSet")
              .setProfileLocation(queryProfileLocationBuilder.create("getApplicationsForApplicant"))
              .findSet()
              .stream()
              .collect(ImmutableSet.toImmutableSet());
        },
        executionContext.current());
  }
}
