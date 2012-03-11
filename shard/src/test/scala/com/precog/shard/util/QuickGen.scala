/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.shard.util

import com.precog.common.util._

import blueeyes.json.Printer
import blueeyes.json.JsonAST._

object QuickGen extends App {
  
  import AdSamples._

  val datasets = Map( 
    ("campaigns"      -> adCampaignSample),
    ("organizations"  -> adOrganizationSample),
    ("clicks"         -> interactionSample),
    ("impressions"    -> interactionSample2),
    ("users"          -> usersSample),
    ("orders"         -> ordersSample),
    ("payments"       -> paymentsSample),
    ("pageViews"      -> pageViewsSample),
    ("customers"      -> customersSample)
  )

  def usage() {
    println(
"""
Usage:

   command {dataset} {quantity}
"""
    )
  }

  def run(dataset: String, events: Int) {
    val sampler = datasets.get(dataset).getOrElse(sys.error("Unknown dataset name: " + dataset))
    val sampleSet = DistributedSampleSet(0, sampler = sampler)
    val sample = 0.until(events).map{ _ => sampleSet.next._1 }.toList
    println(Printer.pretty(Printer.render(JArray(sample))))
  }

  if(args.size < 2) {
    usage()
    System.exit(1)
  } else {
    run(args(0), args(1).toInt)
  }
}