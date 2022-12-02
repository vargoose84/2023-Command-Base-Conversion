// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import com.kauailabs.navx.frc.AHRS;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.numbers.N5;
import edu.wpi.first.math.numbers.N7;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.I2C.Port;
import edu.wpi.first.wpilibj.smartdashboard.Field2d;
import edu.wpi.first.wpilibj2.command.CommandBase;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.subsystems.swerve.QuadFalconSwerveDrive;
import frc.robot.subsystems.swerve.SwerveConstants;
import frc.robot.subsystems.swerve.SwerveModule;
import frc.robot.util.SimGyroSensorModel;
import io.github.oblarg.oblog.Loggable;
import io.github.oblarg.oblog.annotations.Log;

public class SwerveDrive extends SubsystemBase implements Loggable {
  QuadFalconSwerveDrive m_driveTrain;
  Pose2d prevRobotPose = new Pose2d();
  Pose2d robotPose = new Pose2d();
  double deltaTime = 0;
  double prevTime = 0;
  AHRS gyro;
  SimGyroSensorModel simNavx; 
  SwerveDrivePoseEstimator<N7,N7,N5> m_odometry; 

  @Log
  public Field2d m_field;

  public SwerveDrive() {
    //NAVX gyro and sim setup
    gyro = new AHRS(Port.kMXP);
    gyro.reset(); 
    simNavx = new SimGyroSensorModel();

    //SwerveDrive Setup
    m_driveTrain = new QuadFalconSwerveDrive();
    m_driveTrain.checkAndSetSwerveCANStatus();
    m_driveTrain.checkAndZeroSwerveAngle();
    
    //helps visualize robot on virtual field
    m_field = new Field2d();
    
    //Uses 2023 new Pose Estimators which is a drop-in replacement(mostly) for odometry
    m_odometry =     
    new SwerveDrivePoseEstimator<N7, N7, N5>(
      Nat.N7(),
      Nat.N7(),
      Nat.N5(),
      getRobotAngle(),
      m_driveTrain.getModulePositions(),
      new Pose2d(),
      m_driveTrain.m_kinematics,
      VecBuilder.fill(0.05, 0.05, Units.degreesToRadians(5), 0.05, 0.05, 0.05, 0.05),
      VecBuilder.fill(Units.degreesToRadians(0.01), 0.01, 0.01, 0.01, 0.01),
      VecBuilder.fill(0.5, 0.5, Units.degreesToRadians(30)));
  }

  @Override
  public void periodic() {
   
    deltaTime = Timer.getFPGATimestamp() - prevTime;
    prevTime = Timer.getFPGATimestamp();
    //System.out.println(deltaTime);
    if(RobotBase.isSimulation()) {
      for(SwerveModule module : m_driveTrain.SwerveModuleList) {
        module.simModule.simulationPeriodic(deltaTime);
      }    
      System.out.println(robotPose.getRotation().getDegrees() - prevRobotPose.getRotation().getDegrees());   
    }
    prevRobotPose = robotPose;
    robotPose = updateOdometry();
    if(RobotBase.isSimulation()) {
      simNavx.update(robotPose, prevRobotPose, deltaTime);
    }
    //commented this line out due to 
    // m_driveTrain.checkAndSetSwerveCANStatus();
    drawRobotOnField(m_field);
  }


  /**
   * @param _x
   * @param _y
   * @param _rot
   * @param driveGovernor
   * @param fieldRelative
   * @param acceleratedInputs
   */
  public CommandBase joystickDriveCommand(DoubleSupplier _x, DoubleSupplier _y, DoubleSupplier _rot, DoubleSupplier driveGovernor, BooleanSupplier fieldRelative, BooleanSupplier acceleratedInputs){
    return Commands.run(
      () -> {
        double x = _x.getAsDouble();
        double y = _y.getAsDouble();
        double rot = _rot.getAsDouble();;
        double joystickDriveGovernor = driveGovernor.getAsDouble();
        
        if (acceleratedInputs.getAsBoolean()) {

        } else {
          x = Math.signum(x) * Math.sqrt(Math.abs(x));
          y = Math.signum(y) * Math.sqrt(Math.abs(y));
          rot = Math.signum(rot) * Math.sqrt(Math.abs(rot));
        }
        setDriveSpeeds(
          new Translation2d(
            convertToMetersPerSecond(x)*joystickDriveGovernor,
            convertToMetersPerSecond(y)*joystickDriveGovernor), 
          convertToRadiansPerSecond(rot)* joystickDriveGovernor, 
          fieldRelative.getAsBoolean());
        }, this);
  }


  /**
   * @return a Rotation2d populated by the gyro readings 
   * or estimated by encoder wheels (if gyro is disconnected)
   */
  public Rotation2d getRobotAngle(){
    if(RobotBase.isSimulation() && gyro.isConnected()) {

      return simNavx.getRotation2d();

    
    } else if (gyro.isConnected()){
        return gyro.getRotation2d();
    } else {
        //System.out.println( deltaTime);
        return prevRobotPose.getRotation().rotateBy(new Rotation2d(m_driveTrain.m_kinematics.toChassisSpeeds(m_driveTrain.getModuleStates()).omegaRadiansPerSecond *deltaTime));   
    }
    
  }

  @Log
  public double getRobotAngleDegrees() {
    return getRobotAngle().getDegrees();
  }

  /**
   * @param xySpeedsMetersPerSec (X is Positive forward, Y is Positive Right)
   * @param rRadiansPerSecond 
   * @param fieldRelative (SUGGESTION: Telop use field centric, AUTO use robot centric)
   */
  public void setDriveSpeeds(Translation2d xySpeedsMetersPerSec, double rRadiansPerSecond, boolean fieldRelative){
    SwerveModuleState[] swerveModuleStates =
      m_driveTrain.m_kinematics.toSwerveModuleStates(
        fieldRelative ? ChassisSpeeds.fromFieldRelativeSpeeds(
                            xySpeedsMetersPerSec.getX(), 
                            xySpeedsMetersPerSec.getY(), 
                            rRadiansPerSecond, 
                            getRobotAngle()
                        )
                        : new ChassisSpeeds(
                            xySpeedsMetersPerSec.getX(), 
                            xySpeedsMetersPerSec.getY(), 
                            rRadiansPerSecond)
                        );
    SwerveDriveKinematics.desaturateWheelSpeeds(swerveModuleStates, SwerveConstants.MAX_SPEED_METERSperSECOND);
    m_driveTrain.setModuleSpeeds(swerveModuleStates);
  }

  /** 
   * Update the SwerveDrivePoseEstimator
  */
  public Pose2d updateOdometry(){
    return m_odometry.update(getRobotAngle(), 
      m_driveTrain.getModuleStates(), 
      m_driveTrain.getModulePositions());
  }

  /**
   * Draw a pose that is based on the robot pose, but shifted by the translation of the module relative to robot center,
   * then rotated around its own center by the angle of the module.
   * @param field
   */
  public void drawRobotOnField(Field2d field) {
    field.setRobotPose(m_odometry.getEstimatedPosition());

    field.getObject("frontLeft").setPose(
      m_odometry.getEstimatedPosition().transformBy(new Transform2d(m_driveTrain.FrontLeftSwerveModule.mTranslation2d, m_driveTrain.FrontLeftSwerveModule.getSwerveModuleState().angle)));
    field.getObject("frontRight").setPose(
      m_odometry.getEstimatedPosition().transformBy(new Transform2d(m_driveTrain.FrontRightSwerveModule.mTranslation2d, m_driveTrain.FrontRightSwerveModule.getSwerveModuleState().angle)));
    field.getObject("backLeft").setPose(
      m_odometry.getEstimatedPosition().transformBy(new Transform2d(m_driveTrain.BackLeftSwerveModule.mTranslation2d, m_driveTrain.BackLeftSwerveModule.getSwerveModuleState().angle)));
    field.getObject("backRight").setPose(
      m_odometry.getEstimatedPosition().transformBy(new Transform2d(m_driveTrain.BackRightSwerveModule.mTranslation2d, m_driveTrain.BackRightSwerveModule.getSwerveModuleState().angle)));
  }

  public void setToBrake(){
    m_driveTrain.setToBrake();
  }

  /**
   * METHOD WILL NOT WORK UNLESS ADDED TO PERIODIC
   */
  public void setToCoast(){
    m_driveTrain.setToCoast();
  }


  private double convertToMetersPerSecond(double _input){
    return _input*SwerveConstants.MAX_SPEED_METERSperSECOND;
  }

  private double convertToRadiansPerSecond(double _input){
    return _input*SwerveConstants.MAX_SPEED_RADIANSperSECOND;
  }

}
